import { Component, ElementRef, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { Capacitor } from '@capacitor/core';
import { IonicModule } from '@ionic/angular';
import { AppStateService } from '../../services/app-state.service';
import { ApiService } from '../../services/api.service';
import { KioskSocketService } from '../../services/kiosk-socket.service';
import { StorageService } from '../../services/storage.service';
import { KioskWebView } from '../../plugins/kiosk-webview.plugin';
import type { PluginListenerHandle } from '@capacitor/core';
import type { Command } from '../../models/types';

const APP_VERSION = '1.0.0';
const HEARTBEAT_INTERVAL_MS = 30_000;

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
  private appStartMs = Date.now();
  private listenerHandles: PluginListenerHandle[] = [];

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
            console.log('[kiosk] page finished loading:', data.url);
          })
        );
        this.listenerHandles.push(
          await KioskWebView.addListener('pageLoadError', (data) => {
            console.log('[kiosk] page load error:', data.url, data.description);
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

    const creds = this.appState.creds();
    if (!creds) return;

    // WebSocket: instant command delivery while the app is running.
    this.socket.command$.subscribe((command) => this.handleCommand(command));
    this.socket.connect(this.api.wsUrl(creds));

    // Heartbeat: works even if the socket is momentarily down, and is how
    // the server marks the device "online" for the dashboard.
    this.tickHeartbeat();
    this.heartbeatTimer = setInterval(() => this.tickHeartbeat(), HEARTBEAT_INTERVAL_MS);
  }

  async ngOnDestroy(): Promise<void> {
    this.socket.close();
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer);
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

  private async tickHeartbeat(): Promise<void> {
    const creds = this.appState.creds();
    if (!creds) return;

    try {
      const res = await this.api.heartbeat(creds, {
        cpu: 0, // see device-info.service.ts for how to wire up real readings
        ramUsedMb: 0,
        ramTotalMb: 0,
        diskUsedGb: 0,
        diskTotalGb: 0,
        currentUrl: this.url,
        uptimeSeconds: Math.round((Date.now() - this.appStartMs) / 1000),
        appVersion: APP_VERSION,
        networkType: 'wifi',
      });
      for (const cmd of res.pendingCommands as Command[]) {
        this.handleCommand(cmd);
      }
    } catch {
      // offline — heartbeat retries on the next interval, WS reconnect handles the rest.
    }
  }

  private async handleCommand(command: Command): Promise<void> {
    const creds = this.appState.creds();
    if (!creds) return;

    try {
      switch (command.type) {
        case 'open_url': {
          const target = command.payload['url'] as string;
          await this.storage.setUrlOverride(target);
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
          // Web-level "restart": reload the display. A real process restart
          // isn't meaningful for a Capacitor app the way it is for a native
          // RN/Electron process.
          await this.reloadDisplay();
          break;
        case 'update_config': {
          const cfg = command.payload['config'] as { homepage?: string; zoomLevel?: number } | undefined;
          const urlOverride = await this.storage.getUrlOverride();
          if (cfg?.homepage && cfg.homepage !== 'about:blank' && !urlOverride) {
            await this.showUrl(cfg.homepage);
          }

          const zoomOverride = await this.storage.getZoomOverride();
          if (cfg?.zoomLevel && zoomOverride === null && !this.useIframeFallback) {
            this.appState.zoomLevel.set(cfg.zoomLevel);
            await KioskWebView.setZoom({ percent: cfg.zoomLevel }).catch((err) =>
              console.log('[kiosk] setZoom (from update_config) failed:', err)
            );
          }
          break;
        }
        case 'screenshot': {
          if (this.useIframeFallback) {
            // Cross-origin <iframe> content can't be captured via canvas —
            // browsers block this as a security measure (a "tainted canvas"
            // security error), unlike the native WebView path below.
            throw new Error('screenshot unavailable in iframe fallback mode (cross-origin canvas restriction)');
          }
          const { base64 } = await KioskWebView.captureScreenshot();
          await this.api.uploadScreenshot(creds, base64);
          break;
        }
        // reboot_device / shutdown_device / play_sound need native plugins
        // or device-owner permissions — see README.
        default:
          break;
      }
      this.socket.sendAck(command.id, 'acked');
      await this.api.ackCommand(creds, command.id, 'acked').catch(() => {});
    } catch (err) {
      this.socket.sendAck(command.id, 'failed', String(err));
      await this.api.ackCommand(creds, command.id, 'failed', String(err)).catch(() => {});
    }
  }
}