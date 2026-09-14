# Kiosk Ionic App (Ionic 8 + Angular 20 + Capacitor 8 — Android TV / Google TV)

Ionic/Angular rewrite of the kiosk client, replacing the React Native build.
Same feature set: fullscreen display of an assigned URL, live commands over
WebSocket, a settings screen with D-pad/keyboard navigation.

## Network monitoring

The Android build monitors the default network and connected Wi-Fi SSID. The
last verified SSID is retained while the same Wi-Fi transport remains connected,
because Android may redact `WifiInfo` when the activity moves to the background.
allowed SSID array comes from the device's remote config (`allowedSsids`), so
admins can change policy without rebuilding the APK. Exact matches are allowed;
any other SSID triggers a native Android Toast above the kiosk WebView and is
reported to the server as `wrong_wifi`. An empty array allows any Wi-Fi.

Android deliberately redacts SSID unless the user grants Nearby devices and
precise-location access (and location services are enabled). The app requests
the required permission on first launch. Managed kiosk deployments should
pre-grant both permissions through their device-management policy.

The app sends a REST heartbeat every 15 seconds and verifies the server's ACK
sequence. It also answers WebSocket `ping` messages with `pong`. Network loss,
recovery, wrong Wi-Fi, and server-heartbeat changes produce native status
alerts. A pending network-loss reason is kept in Capacitor Preferences and sent
after connectivity returns.

Each app start creates a new heartbeat session ID. The Fastify server updates
one database row for that session instead of inserting a row for every
heartbeat, substantially reducing database growth on always-on kiosk devices.

## Full kiosk browser behavior

- The Ionic shell keeps its normal responsive viewport:
  `width=device-width, initial-scale=1, viewport-fit=cover`. It has not been
  removed.
- The separate native content WebView presents loaded websites with a desktop
  1920×1080 viewport and desktop user agent. The website's viewport tag is
  rewritten for that native browser surface; it is not removed from the app.
- The native browser always fills a complete 1920×1080 virtual canvas, even for
  short pages, then scales that canvas to the physical TV resolution.
- Android status/navigation bars are hidden using immersive mode and the screen
  stays awake. A slim native progress bar appears during navigation.
- A non-reference-counted Android Wi-Fi lock keeps the radio awake while the
  kiosk app is running. Android 10+ uses low-latency mode; older devices use
  high-performance mode. This prevents idle power-saving disconnects, but does
  not override disabled Wi-Fi, Airplane Mode, weak signal, or AP outages.
- JavaScript, DOM storage, cookies, third-party cookies, browser cache, media
  autoplay and compatible mixed content are enabled for dashboard compatibility.
- HTTP URLs are allowed for internal dashboards. Prefer HTTPS whenever a valid
  internal certificate is available.

**Verified in this environment:**
- `ng build` (dev and production configurations) — clean, zero errors
- `npx cap sync android` — recognizes `@capacitor/app` and `@capacitor/preferences`, copies web assets in
- All native Java (`MainActivity.java`, `WifiSettingsPlugin.java`) checked against the actual installed Capacitor source in `node_modules`, not guessed
- Manifest XML is well-formed

**Not verified** (no Android SDK or Gradle-distribution network access in this
sandbox): the actual `./gradlew assembleDebug` compile step. Everything
upstream of it checks out; run that build on your machine to confirm.

## Why this instead of the React Native version

- **TLS bypass is one native file, not two.** The whole Capacitor app —
  `fetch()`, WebSocket, and page loads — runs inside a single WebView, so
  `MainActivity.java`'s `onReceivedSslError` override covers everything. The
  RN version needed separate hooks for `fetch()` (`OkHttpClientProvider`) and
  WebSocket (`WebSocketModule.setCustomClientBuilder`), because RN's JS
  runtime lives outside any WebView.
- **Trade-off**: D-pad/remote navigation on the settings screen is
  arrow-key-based (see `settings.page.ts`) rather than RN's native
  `hasTVPreferredFocus` — works, but native focus handling is generally more
  polished on Android TV.

## The kiosk display is a native WebView, not an iframe

Earlier versions of this app displayed the assigned URL in an `<iframe>`
nested inside the Angular page. That broke on any site sending
`X-Frame-Options` or `Content-Security-Policy: frame-ancestors` — which
includes Google, YouTube, Facebook, and plenty of others — since those
headers exist specifically to prevent a page from being embedded inside
someone else's page. No client-side workaround gets around that; it's the
target site refusing to be framed, enforced by the browser engine itself.

The fix: `KioskWebViewPlugin.java` manages a **separate, second native
`WebView`** layered on top of the Capacitor app (via `addContentView`),
which loads the assigned URL as its own top-level page — not nested in
anything, so `X-Frame-Options` never applies. This is the same approach
`react-native-webview` uses. `KioskPage` calls `KioskWebView.show({url})` /
`.hide()` / `.reload()` / `.clearCacheAndReload()` instead of rendering an
`<iframe>`; toggling between the kiosk display and settings screen just
shows/hides this native view.

**Consequence**: this plugin is Android-only by nature (it manipulates a
native `WebView` directly). `KioskPage` checks `Capacitor.getPlatform()`
synchronously on load — if there's no native bridge (a plain browser, e.g.
`ionic serve`), or if a real call to the plugin throws on an actual device,
it automatically falls back to an `<iframe>` instead of going blank. The
debug banner shows `(iframe fallback)` when this has happened. Trade-off:
in fallback mode, sites that refuse to be framed (Google, YouTube, etc.)
still won't display — but at least something shows instead of nothing.

## Project layout

```
src/app/
  models/types.ts                    # mirrors kiosk-server/src/types.ts — keep in sync
  services/
    storage.service.ts               # Capacitor Preferences: credentials and cached config
    api.service.ts                   # register / heartbeat / ack / fetch + update config
    kiosk-socket.service.ts          # WebSocket client, auto-reconnect w/ backoff
    app-state.service.ts             # boot sequence (register, resolve homepage), signals
    remote-back.service.ts           # back button -> opens settings (Capacitor App plugin)
    device-info.service.ts           # basic device info (screen size, platform)
  plugins/
    wifi-settings.plugin.ts          # JS wrapper for the custom native Wi-Fi plugin
    kiosk-webview.plugin.ts          # JS wrapper for the native kiosk display WebView
  pages/
    kiosk/                           # thin loading/debug overlay — actual display is the native WebView
    settings/                       # Wi-Fi / synchronized URL + zoom / device info
  app.ts / app.html / app.scss       # root: boot/error/kiosk/settings switching

android/
  app/src/main/java/com/kiosktvapp/
    MainActivity.java                # registers plugins + TLS bypass WebViewClient for the Capacitor WebView
    WifiSettingsPlugin.java          # custom plugin: opens Android's Wi-Fi settings screen
    KioskWebViewPlugin.java          # custom plugin: separate native WebView for the kiosk display (not an iframe)
  app/src/main/AndroidManifest.xml   # Leanback launcher, TV uses-features, Wi-Fi permissions
  app/src/main/res/drawable/tv_banner.png  # placeholder TV banner (320x180) — replace with real branding
```

## Before you build: set server and enrollment values

Edit `src/environments/environment.ts`:
```ts
export const environment = {
  kioskServerUrl: 'https://vision-api.ijn.com.my',
  enrollmentCode: '',
  appVersion: '1.0.0',
} as const;
```

Leave `enrollmentCode` empty when the server's `ENROLLMENT_CODE` is empty. If
enrollment protection is enabled, build the APK with the matching value and
rotate or clear it on the server after enrollment; a value compiled into an
APK should not be treated as a permanent secret.

## Build and run

```bash
npm install
npm run build                 # ng build, outputs to dist/app/browser
npx cap sync android          # copies web build + plugins into the native project
cd android
./gradlew assembleDebug
adb -s <tv-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <tv-ip>:5555 shell am start -n lm.ijn.kiosktvapp/.MainActivity
```

**Important**: unlike a plain web app, `npx cap sync android` must be re-run
after every `ng build` before the APK will reflect your changes — Capacitor
copies the built web assets into the native project as a static snapshot,
it doesn't reference `dist/` live.

## How it behaves

Same boot sequence as the RN version, with the same debug logging:
```
[kiosk] starting, server = https://vision-api.ijn.com.my
[kiosk] stored creds: ...
[kiosk] no stored creds — registering as tv-xxxxx
[kiosk] register() succeeded: {...}
[kiosk] fetchMyConfig() succeeded: {...}
```
Watch these via `adb logcat` — Capacitor forwards `console.log` from the
WebView to Logcat under the tag `Capacitor/Console`.

- **Config synchronization**: the registration response is applied immediately,
  the last good config is kept for offline startup, config is fetched again at
  every WebSocket connection/reconnection, and live `update_config` commands
  are applied without restarting the app.
- **Two-way settings**: changing the assigned URL or zoom sends an authenticated
  `PUT /api/my/config`. The server validates and saves it in MySQL, so the admin
  dashboard and every future reconnect see the same value. Older local override
  keys are cleared once during migration.
- **Live commands**: `open_url`, `reload`, `clear_cache`, `restart_app`,
  `screenshot`, `play_sound`, and `update_config` are handled in
  `kiosk.page.ts`. Unsupported device-owner commands return a failed
  acknowledgement instead of being reported as successful.
- **Settings screen**: press the remote's back button — opens after a short
  delay (same approximation as the RN build; Capacitor's `backButton` event,
  like RN's `BackHandler`, fires once per press rather than giving true
  hold-duration timing). Arrow keys (Up/Down) move focus between rows; Enter
  activates. The focus ring (`.focus-row:focus` in `settings.page.scss`) is
  the only "cursor" a remote user has, so it's intentionally high-contrast.

## Remote configuration behavior

| Field | Ionic/Android behavior |
|---|---|
| `homepage` | Loads in the native kiosk WebView; device edits sync back to MySQL. |
| `reloadEverySeconds` | Reloads periodically; `null` disables it. |
| `allowNavigation` | `false` keeps navigation on the assigned URL's origin; `true` allows external origins. |
| `showCursor` | Injects `cursor: none` or restores the page cursor after every load. |
| `takeScreenshotEverySeconds` | Captures and uploads the native WebView periodically; `null` disables it. |
| `zoomLevel` | Applies native view-level zoom; device edits sync back to MySQL. |
| `autoUpdate` | Supports admin-triggered, authenticated APK download with SHA-256 verification. Android shows package-installer confirmation unless the device is system-managed. |

Periodic work is replaced whenever config changes, so old timers do not remain
active. Command and reconnect subscriptions are also disposed when the kiosk
view closes for the settings screen.

## Features that require Device Owner or update infrastructure

| Feature | Current behavior | Requirement |
|---|---|---|
| `reboot_device` / `shutdown_device` | Returns a failed acknowledgement | Device Owner/COSU provisioning plus a native `DevicePolicyManager` plugin |
| Silent APK installation | Opens Android's verified package-installer flow | Device Owner/system provisioning is required to remove confirmation |
| Real CPU/RAM/disk in heartbeat | Sent as zeros | Add the `@capacitor/device` plugin — see the comment in `device-info.service.ts` |

## Locking it down further (optional, for real kiosk deployments)

Same recommendations as the RN build: Device Owner/COSU provisioning for
reboot/shutdown and to prevent the launcher from being backgrounded, a
`BOOT_COMPLETED` receiver for auto-launch after power loss, and a proper
Network Security Config with a pinned certificate instead of the blanket TLS
bypass if this app ever needs to talk to a server outside your internal
network.
