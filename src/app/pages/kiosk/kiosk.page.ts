import { Component, ElementRef, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { Capacitor } from '@capacitor/core';
import { App } from '@capacitor/app';
import { IonicModule } from '@ionic/angular';
import { AppStateService } from '../../services/app-state.service';
import { ApiService } from '../../services/api.service';
import type { DeviceConsoleEntry } from '../../services/api.service';
import { KioskSocketService } from '../../services/kiosk-socket.service';
import { StorageService } from '../../services/storage.service';
import { KioskWebView } from '../../plugins/kiosk-webview.plugin';
import { NetworkMonitor } from '../../plugins/network-monitor.plugin';
import { AppUpdate } from '../../plugins/app-update.plugin';
import type { NetworkStatus } from '../../plugins/network-monitor.plugin';
import type { PluginListenerHandle } from '@capacitor/core';
import type { Subscription } from 'rxjs';
import type { Command, DeviceConfig, HeartbeatPayload } from '../../models/types';
import { environment } from '../../../environments/environment';

const HEARTBEAT_INTERVAL_MS = 15_000;
const CONSOLE_FLUSH_INTERVAL_MS = 3_000;
const APP_SESSION_STARTED_AT = new Date().toISOString();
const APP_SESSION_ID = globalThis.crypto?.randomUUID?.() ??
  `session-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;

@Component({
  selector: 'app-kiosk',
  standalone: true,
  imports: [IonicModule],
  templateUrl: './kiosk.page.html',
  styleUrl: './kiosk.page.scss',
})
export class KioskPage implements OnInit, OnDestroy {
  @ViewChild('kioskFrame') kioskFrame?: ElementRef<HTMLIFrameElement>;

  url = '';
  safeUrl: SafeResourceUrl = '';
  loading = true;
  statusMessage = '';
  statusTone: 'warning' | 'danger' = 'warning';

  /**
   * True when there's no native bridge at all (a plain browser, e.g.
   * `ionic serve`) — checked synchronously via Capacitor.getPlatform()
   * BEFORE Angular's first render, so the @if in the template is correct
   * from the very first change-detection pass. (An earlier version decided
   * this asynchronously inside ngOnInit by letting the plugin call throw,
   * which caused an ExpressionChangedAfterItHasBeenCheckedError in dev mode
   * — flipping a template-bound flag after Angular had already rendered
   * once with the old value.)
   *
   * The try/catch in ngOnInit below still exists as a safety net for a
   * genuine native-side failure on a real device (rare — e.g. WebView
   * creation failing) — that path flips this later, during normal async
   * command handling, not during the initial render, so it doesn't hit the
   * same timing issue.
   */
  useIframeFallback = Capacitor.getPlatform() === 'web';

  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private reloadTimer: ReturnType<typeof setInterval> | null = null;
  private screenshotTimer: ReturnType<typeof setInterval> | null = null;
  private consoleFlushTimer: ReturnType<typeof setInterval> | null = null;
  private reloadInFlight = false;
  private screenshotInFlight = false;
  private appStartMs = Date.now();
  private listenerHandles: PluginListenerHandle[] = [];
  private subscriptions: Subscription[] = [];
  private heartbeatRequestsInFlight = 0;
  private heartbeatSequence = 0;
  private heartbeatFailed = false;
  private appIsActive = true;
  private consoleQueue: DeviceConsoleEntry[] = [];
  private consoleUploadInFlight = false;
  private queuedHeartbeatOverride: Partial<
    Pick<HeartbeatPayload, 'networkState' | 'appState' | 'lastDisconnectReason'>
  > | null = null;
  private networkStatus: NetworkStatus = {
    connected: navigator.onLine,
    networkState: navigator.onLine ? 'connected' : 'disconnected',
    networkType: 'unknown',
    ssid: null,
    wifiPolicyStatus: 'unknown',
    allowedSsids: [],
    occurredAt: Date.now(),
  };

  constructor(
    public appState: AppStateService,
    private api: ApiService,
    private socket: KioskSocketService,
    private storage: StorageService,
    private sanitizer: DomSanitizer
  ) {}

  async ngOnInit(): Promise<void> {
    const initialUrl = this.appState.homepage();

    if (this.useIframeFallback) {
      await this.showUrl(initialUrl);
    } else {
      try {
        this.listenerHandles.push(
          await KioskWebView.addListener('pageLoadStart', () => {
            setTimeout(() => (this.loading = true), 0);
          })
        );
        this.listenerHandles.push(
          await KioskWebView.addListener('pageLoadFinished', (data) => {
            setTimeout(() => (this.loading = false), 0);
            this.url = data.url;
            console.log('[kiosk] page finished loading:', data.url);
          })
        );
        this.listenerHandles.push(
          await KioskWebView.addListener('pageLoadError', (data) => {
            console.log('[kiosk] page load error:', data.url, data.description);
            this.queueConsoleEntry({
              level: 'error',
              message: `Page load failed: ${data.description}`,
              source: data.url,
              line: 0,
              timestamp: Date.now(),
            });
          })
        );
        this.listenerHandles.push(
          await KioskWebView.addListener('consoleMessage', (data) => {
            this.queueConsoleEntry(data);
          })
        );
        await this.showUrl(initialUrl);
      } catch (err) {
        // Genuine native-side failure on a real device (rare) — this flip
        // happens well after the initial render, not during it, so it
        // doesn't hit the ExpressionChangedAfterItHasBeenCheckedError timing
        // issue described above.
        console.log('[kiosk] KioskWebView native plugin failed, falling back to iframe:', err);
        this.useIframeFallback = true;
        await this.showUrl(initialUrl);
      }
    }

    if (!this.useIframeFallback) {
      // Both are plain Android View transforms (setScaleX/Y, setRotation)
      // set once here — they're properties of the WebView itself, not the
      // page content, so they don't get reset by navigation and don't need
      // re-applying on every page load. Not applicable in iframe fallback
      // mode: cross-origin iframe content can't have JS injected into it
      // (irrelevant here anyway, since these are native View transforms,
      // not JS — but the fallback WebView itself is a different, plain
      // Capacitor-managed element these don't apply to).
      try {
        await KioskWebView.setZoom({ percent: this.appState.zoomLevel() });
        await KioskWebView.setRotation({ degrees: await this.storage.getRotationDegrees() });
      } catch (err) {
        console.log('[kiosk] setZoom/setRotation failed:', err);
      }
    }

    await this.applyRuntimeConfig(this.appState.remoteConfig());

    if (!this.useIframeFallback) {
      try {
        this.listenerHandles.push(
          await NetworkMonitor.addListener('networkStatusChanged', (status) => {
            void this.handleNetworkStatus(status);
          })
        );
        const initialNetwork = await NetworkMonitor.startMonitoring({
          allowedSsids: this.appState.remoteConfig().allowedSsids,
        });
        await this.handleNetworkStatus(initialNetwork, false);
      } catch (err) {
        console.log('[kiosk] native network monitor unavailable:', err);
      }
    }

    this.listenerHandles.push(
      await App.addListener('appStateChange', ({ isActive }) => {
        this.appIsActive = isActive;
        if (!isActive) {
          void this.storage.setPendingDisconnectReason('app_backgrounded');
          // Urgent + fetch keepalive makes the final lifecycle heartbeat much
          // more likely to finish while Android is pausing the WebView.
          void this.tickHeartbeat(
            { appState: 'background', lastDisconnectReason: 'app_backgrounded' },
            true
          );
        } else {
          void this.tickHeartbeat({ appState: 'active' });
        }
      })
    );

    const creds = this.appState.creds();
    if (!creds) return;

    // WebSocket: instant command delivery while the app is running.
    this.subscriptions.push(
      this.socket.command$.subscribe((command) => void this.handleCommand(command)),
      this.socket.open$.subscribe(() => void this.refreshConfigFromServer())
    );
    this.socket.connect(this.api.wsUrl(creds));

    this.queueConsoleEntry({
      level: 'info',
      message: `Kiosk app v${environment.appVersion} diagnostic session started`,
      source: 'IonicApp',
      line: 0,
      timestamp: Date.now(),
    });
    this.consoleFlushTimer = setInterval(
      () => void this.flushConsoleEntries(),
      CONSOLE_FLUSH_INTERVAL_MS
    );

    // Heartbeat: works even if the socket is momentarily down, and is how
    // the server marks the device "online" for the dashboard.
    this.tickHeartbeat();
    this.heartbeatTimer = setInterval(() => this.tickHeartbeat(), HEARTBEAT_INTERVAL_MS);
  }

  async ngOnDestroy(): Promise<void> {
    this.socket.close();
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer);
    if (this.reloadTimer) clearInterval(this.reloadTimer);
    if (this.screenshotTimer) clearInterval(this.screenshotTimer);
    if (this.consoleFlushTimer) clearInterval(this.consoleFlushTimer);
    await this.flushConsoleEntries();
    for (const subscription of this.subscriptions) subscription.unsubscribe();
    if (!this.useIframeFallback) {
      try {
        for (const handle of this.listenerHandles) await handle.remove();
        await KioskWebView.hide();
      } catch {
        // already unavailable — nothing to clean up
      }
    }
  }

  onFrameLoad(): void {
    // Deferred to the next macrotask: a very fast-loading iframe (cached or
    // local URL) can fire this DOM `load` event within the same
    // change-detection cycle Angular's dev-mode verification pass checks,
    // producing an ExpressionChangedAfterItHasBeenCheckedError. Letting it
    // settle one tick later avoids that without changing user-visible timing.
    setTimeout(() => {
      this.loading = false;
    }, 0);
    console.log('[kiosk] iframe finished loading:', this.url);
  }

  /** Displays a URL via whichever mechanism is currently active, falling
   * back to the iframe the first time the native plugin throws. */
  private async showUrl(target: string): Promise<void> {
    this.url = target;

    if (this.useIframeFallback) {
      this.safeUrl = this.sanitizer.bypassSecurityTrustResourceUrl(target);
      this.loading = true;
      return;
    }

    try {
      await KioskWebView.show({ url: target });
    } catch (err) {
      console.log('[kiosk] KioskWebView.show() failed, falling back to iframe:', err);
      this.useIframeFallback = true;
      this.safeUrl = this.sanitizer.bypassSecurityTrustResourceUrl(target);
      this.loading = true;
    }
  }

  private async reloadDisplay(bustCache = false): Promise<void> {
    if (this.useIframeFallback) {
      const frame = this.kioskFrame?.nativeElement;
      if (!frame) return;
      if (bustCache) {
        const sep = this.url.includes('?') ? '&' : '?';
        const busted = `${this.url}${sep}_cb=${Date.now()}`;
        this.safeUrl = this.sanitizer.bypassSecurityTrustResourceUrl(busted);
      } else {
        // Re-assigning src is the standard cross-browser way to force an
        // iframe reload (contentWindow.location.reload() is blocked by
        // cross-origin restrictions for external kiosk URLs).
        frame.src = frame.src;
      }
      return;
    }

    try {
      if (bustCache) await KioskWebView.clearCacheAndReload();
      else await KioskWebView.reload();
    } catch (err) {
      console.log('[kiosk] KioskWebView reload failed, falling back to iframe:', err);
      this.useIframeFallback = true;
      await this.showUrl(this.url);
    }
  }

  private async tickHeartbeat(
    override: Partial<Pick<HeartbeatPayload, 'networkState' | 'appState' | 'lastDisconnectReason'>> = {},
    urgent = false
  ): Promise<void> {
    const creds = this.appState.creds();
    if (!creds) return;
    if (this.heartbeatRequestsInFlight > 0 && !urgent) {
      this.queuedHeartbeatOverride = { ...this.queuedHeartbeatOverride, ...override };
      return;
    }
    this.heartbeatRequestsInFlight += 1;

    try {
      const pendingReason = await this.storage.getPendingDisconnectReason();
      const sequence = ++this.heartbeatSequence;
      const res = await this.api.heartbeat(creds, {
        sessionId: APP_SESSION_ID,
        sessionStartedAt: APP_SESSION_STARTED_AT,
        cpu: 0, // see device-info.service.ts for how to wire up real readings
        ramUsedMb: 0,
        ramTotalMb: 0,
        diskUsedGb: 0,
        diskTotalGb: 0,
        currentUrl: this.url,
        uptimeSeconds: Math.round((Date.now() - this.appStartMs) / 1000),
        appVersion: environment.appVersion,
        networkType: this.networkStatus.networkType,
        ssid: this.networkStatus.ssid,
        wifiPolicyStatus: this.networkStatus.wifiPolicyStatus,
        networkState: override.networkState ?? this.networkStatus.networkState,
        appState: override.appState ?? (this.appIsActive ? 'active' : 'background'),
        lastDisconnectReason: override.lastDisconnectReason ?? pendingReason,
        clientSentAt: new Date().toISOString(),
        sequence,
      }, urgent);
      if (res.heartbeatAck.sequence !== sequence) {
        console.log('[kiosk] heartbeat ACK sequence mismatch:', res.heartbeatAck.sequence, sequence);
      }
      if (pendingReason) await this.storage.setPendingDisconnectReason(null);
      if (this.heartbeatFailed) {
        this.heartbeatFailed = false;
        await this.showStatusAlert('Server connection restored. Heartbeat confirmed.', 'warning');
      }
      for (const cmd of res.pendingCommands) {
        void this.handleCommand(cmd);
      }
    } catch (err) {
      if (this.networkStatus.connected && !this.heartbeatFailed) {
        this.heartbeatFailed = true;
        await this.showStatusAlert('Server heartbeat failed. Device may be shown as offline.', 'danger');
      }
      console.log('[kiosk] heartbeat failed:', err);
    } finally {
      this.heartbeatRequestsInFlight = Math.max(0, this.heartbeatRequestsInFlight - 1);
      if (this.heartbeatRequestsInFlight === 0 && this.queuedHeartbeatOverride) {
        const queued = this.queuedHeartbeatOverride;
        this.queuedHeartbeatOverride = null;
        void this.tickHeartbeat(queued);
      }
    }
  }

  private async handleNetworkStatus(status: NetworkStatus, notify = true): Promise<void> {
    const previous = this.networkStatus;
    this.networkStatus = status;

    if (!status.connected) {
      await this.storage.setPendingDisconnectReason('network_lost');
      this.statusMessage = 'Network connection lost — reconnecting automatically';
      this.statusTone = 'danger';
      void this.tickHeartbeat({ networkState: 'disconnected', lastDisconnectReason: 'network_lost' });
      return;
    }

    if (status.wifiPolicyStatus === 'blocked') {
      this.statusMessage = `Wrong Wi-Fi: ${status.ssid ?? 'unknown'}. Allowed: ${status.allowedSsids.join(', ')}`;
      this.statusTone = 'danger';
    } else if (status.wifiPolicyStatus === 'unknown' && status.networkType === 'wifi') {
      this.statusMessage = 'Wi-Fi name unavailable — grant Nearby devices/location permission';
      this.statusTone = 'warning';
    } else {
      this.statusMessage = '';
    }

    if (!previous.connected && notify && this.useIframeFallback) {
      await this.showStatusAlert(`Network restored${status.ssid ? `: ${status.ssid}` : ''}.`, 'warning');
    }
    void this.tickHeartbeat({ networkState: 'connected' });
  }

  private async showStatusAlert(message: string, tone: 'warning' | 'danger'): Promise<void> {
    this.statusMessage = message;
    this.statusTone = tone;
    if (!this.useIframeFallback) {
      await NetworkMonitor.showAlert({ message }).catch(() => {});
    }
  }

  private async handleCommand(command: Command): Promise<void> {
    const creds = this.appState.creds();
    if (!creds) return;

    try {
      switch (command.type) {
        case 'open_url': {
          const target = command.payload['url'] as string;
          if (!target) throw new Error('open_url requires payload.url');
          await this.showUrl(target);
          break;
        }
        case 'reload':
          await this.reloadDisplay();
          break;
        case 'clear_cache':
          await this.reloadDisplay(true);
          break;
        case 'restart_app':
          window.location.reload();
          break;
        case 'update_config': {
          const patch = command.payload['config'] as Partial<DeviceConfig> | undefined;
          if (!patch) throw new Error('update_config requires payload.config');
          const config = { ...this.appState.remoteConfig(), ...patch };
          await this.appState.applyRemoteConfig(config);
          await this.applyRuntimeConfig(this.appState.remoteConfig());
          break;
        }
        case 'screenshot':
          await this.captureAndUploadScreenshot();
          break;
        case 'play_sound': {
          const soundUrl = command.payload['url'] as string;
          if (!soundUrl) throw new Error('play_sound requires payload.url');
          await new Audio(soundUrl).play();
          break;
        }
        case 'reboot_device':
        case 'shutdown_device':
          throw new Error(`${command.type} requires Android Device Owner provisioning`);
        case 'install_apk': {
          if (this.useIframeFallback) throw new Error('APK installation is only available on Android');
          const downloadPath = command.payload['downloadPath'] as string;
          const sha256 = command.payload['sha256'] as string;
          if (!downloadPath || !sha256) throw new Error('install_apk requires downloadPath and sha256');
          await AppUpdate.downloadAndInstall({
            url: this.api.apkDownloadUrl(downloadPath),
            token: creds.token,
            sha256,
          });
          break;
        }
      }
      this.socket.sendAck(command.id, 'acked');
      await this.api.ackCommand(creds, command.id, 'acked').catch(() => {});
    } catch (err) {
      this.socket.sendAck(command.id, 'failed', String(err));
      await this.api.ackCommand(creds, command.id, 'failed', String(err)).catch(() => {});
    }
  }

  private async refreshConfigFromServer(): Promise<void> {
    try {
      const config = await this.appState.syncRemoteConfig();
      await this.applyRuntimeConfig(config);
    } catch (err) {
      console.log('[kiosk] config refresh after WebSocket connect failed:', err);
    }
  }

  private async applyRuntimeConfig(config: DeviceConfig): Promise<void> {
    const targetUrl = this.appState.homepage();
    if (targetUrl && targetUrl !== this.url && targetUrl !== 'about:blank') {
      await this.showUrl(targetUrl);
    }

    if (!this.useIframeFallback) {
      await Promise.all([
        KioskWebView.setZoom({ percent: this.appState.zoomLevel() }),
        KioskWebView.setNavigationAllowed({ allowed: config.allowNavigation }),
        KioskWebView.setCursorVisible({ visible: config.showCursor }),
        NetworkMonitor.updatePolicy({ allowedSsids: config.allowedSsids }),
      ]).catch((err) => console.log('[kiosk] applying native config failed:', err));
    }

    this.configurePeriodicTasks(config);
  }

  private configurePeriodicTasks(config: DeviceConfig): void {
    if (this.reloadTimer) clearInterval(this.reloadTimer);
    if (this.screenshotTimer) clearInterval(this.screenshotTimer);
    this.reloadTimer = null;
    this.screenshotTimer = null;

    if (config.reloadEverySeconds !== null && config.reloadEverySeconds >= 10) {
      this.reloadTimer = setInterval(
        () => void this.periodicReload().catch((err) => console.log('[kiosk] periodic reload failed:', err)),
        config.reloadEverySeconds * 1000
      );
    }

    if (
      !this.useIframeFallback &&
      config.takeScreenshotEverySeconds !== null &&
      config.takeScreenshotEverySeconds >= 30
    ) {
      this.screenshotTimer = setInterval(
        () =>
          void this.captureAndUploadScreenshot().catch((err) =>
            console.log('[kiosk] periodic screenshot failed:', err)
          ),
        config.takeScreenshotEverySeconds * 1000
      );
    }
  }

  private async periodicReload(): Promise<void> {
    if (this.reloadInFlight) return;
    this.reloadInFlight = true;
    try {
      await this.reloadDisplay();
    } finally {
      this.reloadInFlight = false;
    }
  }

  private async captureAndUploadScreenshot(): Promise<void> {
    if (this.useIframeFallback) {
      throw new Error(
        'screenshot unavailable in iframe fallback mode (cross-origin canvas restriction)'
      );
    }
    if (this.screenshotInFlight) return;

    const creds = this.appState.creds();
    if (!creds) throw new Error('device is not registered');

    this.screenshotInFlight = true;
    try {
      const { base64 } = await KioskWebView.captureScreenshot();
      await this.api.uploadScreenshot(creds, base64);
    } finally {
      this.screenshotInFlight = false;
    }
  }

  private queueConsoleEntry(entry: DeviceConsoleEntry): void {
    this.consoleQueue.push({
      level: String(entry.level || 'log').slice(0, 20),
      message: String(entry.message || '').slice(0, 2000),
      source: String(entry.source || '').slice(0, 500),
      line: Number.isFinite(entry.line) ? entry.line : 0,
      timestamp: Number.isFinite(entry.timestamp) ? entry.timestamp : Date.now(),
    });
    if (this.consoleQueue.length > 500) {
      this.consoleQueue.splice(0, this.consoleQueue.length - 500);
    }
  }

  private async flushConsoleEntries(): Promise<void> {
    if (this.consoleUploadInFlight || !this.consoleQueue.length) return;
    const creds = this.appState.creds();
    if (!creds) return;

    const batch = this.consoleQueue.splice(0, 50);
    this.consoleUploadInFlight = true;
    try {
      await this.api.uploadConsoleEntries(creds, batch);
    } catch {
      this.consoleQueue.unshift(...batch);
      if (this.consoleQueue.length > 500) this.consoleQueue.length = 500;
    } finally {
      this.consoleUploadInFlight = false;
    }
  }
}
