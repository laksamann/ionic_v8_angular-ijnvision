# Kiosk Ionic App (Ionic 8 + Angular 20 + Capacitor 8 — Android TV / Google TV)

Ionic/Angular rewrite of the kiosk client, replacing the React Native build.
Same feature set: fullscreen display of an assigned URL, live commands over
WebSocket, a settings screen with D-pad/keyboard navigation.

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
    storage.service.ts               # Capacitor Preferences: creds, URL override, hostname
    api.service.ts                   # register / heartbeat / ack / fetch config
    kiosk-socket.service.ts          # WebSocket client, auto-reconnect w/ backoff
    app-state.service.ts             # boot sequence (register, resolve homepage), signals
    remote-back.service.ts           # back button -> opens settings (Capacitor App plugin)
    device-info.service.ts           # basic device info (screen size, platform)
  plugins/
    wifi-settings.plugin.ts          # JS wrapper for the custom native Wi-Fi plugin
    kiosk-webview.plugin.ts          # JS wrapper for the native kiosk display WebView
  pages/
    kiosk/                           # thin loading/debug overlay — actual display is the native WebView
    settings/                       # Wi-Fi / URL override / device info, arrow-key navigable
  app.ts / app.html / app.scss       # root: boot/error/kiosk/settings switching

android/
  app/src/main/java/com/kiosktvapp/
    MainActivity.java                # registers plugins + TLS bypass WebViewClient for the Capacitor WebView
    WifiSettingsPlugin.java          # custom plugin: opens Android's Wi-Fi settings screen
    KioskWebViewPlugin.java          # custom plugin: separate native WebView for the kiosk display (not an iframe)
  app/src/main/AndroidManifest.xml   # Leanback launcher, TV uses-features, Wi-Fi permissions
  app/src/main/res/drawable/tv_banner.png  # placeholder TV banner (320x180) — replace with real branding
```

## Before you build: set your server URL

Edit `src/app/services/app-state.service.ts`:
```ts
const SERVER_URL = 'https://testmobile.ijn.com.my';
```

## Build and run

```bash
npm install
npm run build                 # ng build, outputs to dist/kiosk-ionic-app/browser
npx cap sync android          # copies web build + plugins into the native project
cd android
./gradlew assembleDebug
adb -s <tv-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <tv-ip>:5555 shell am start -n com.kiosktvapp/.MainActivity
```

**Important**: unlike a plain web app, `npx cap sync android` must be re-run
after every `ng build` before the APK will reflect your changes — Capacitor
copies the built web assets into the native project as a static snapshot,
it doesn't reference `dist/` live.

## How it behaves

Same boot sequence as the RN version, with the same debug logging:
```
[kiosk] starting, server = https://testmobile.ijn.com.my
[kiosk] stored creds: ...
[kiosk] no stored creds — registering as tv-xxxxx
[kiosk] register() succeeded: {...}
[kiosk] fetchMyConfig() succeeded: {...}
```
Watch these via `adb logcat` — Capacitor forwards `console.log` from the
WebView to Logcat under the tag `Capacitor/Console`.

- **Homepage resolution order**: local URL override → server-assigned
  `homepage` (rejecting `about:blank` the same way the RN version does) →
  hardcoded fallback.
- **Live commands**: `open_url`, `reload`, `clear_cache`, `update_config` are
  handled in `kiosk.page.ts`. `reload`/`clear_cache` re-assign the iframe's
  `src` (with a cache-busting query param for `clear_cache`), since an
  `<iframe>` has no direct cache-clear API the way RN's `WebView` component did.
- **Settings screen**: press the remote's back button — opens after a short
  delay (same approximation as the RN build; Capacitor's `backButton` event,
  like RN's `BackHandler`, fires once per press rather than giving true
  hold-duration timing). Arrow keys (Up/Down) move focus between rows; Enter
  activates. The focus ring (`.focus-row:focus` in `settings.page.scss`) is
  the only "cursor" a remote user has, so it's intentionally high-contrast.

## Deliberately stubbed / needs a native plugin

Same categories as the RN build — recognized and acked, but not fully wired:

| Command | Current behavior | To make it real |
|---|---|---|
| `restart_app` | Reloads the iframe only | Not really meaningful for a WebView app the way a native process restart is |
| `screenshot` | Not implemented | Would need a native plugin to capture the WebView's rendered output |
| `reboot_device` / `shutdown_device` | Not implemented | Requires Device Owner (COSU) provisioning + a native plugin calling `DevicePolicyManager` |
| `play_sound` | Not implemented | HTML5 `<audio>` element works for simple cases without any native plugin |
| Real CPU/RAM/disk in heartbeat | Sent as zeros | Add the `@capacitor/device` plugin — see the comment in `device-info.service.ts` |

## Locking it down further (optional, for real kiosk deployments)

Same recommendations as the RN build: Device Owner/COSU provisioning for
reboot/shutdown and to prevent the launcher from being backgrounded, a
`BOOT_COMPLETED` receiver for auto-launch after power loss, and a proper
Network Security Config with a pinned certificate instead of the blanket TLS
bypass if this app ever needs to talk to a server outside your internal
network.
"# ionic_v8_angular-ijnvision" 
