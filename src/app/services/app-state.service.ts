import { Injectable, signal } from '@angular/core';
import { StorageService } from './storage.service';
import { ApiService } from './api.service';
import { DeviceName } from '../plugins/device-name.plugin';
import type { DeviceCreds, DeviceConfig } from '../models/types';

// Point this at your Fastify server. In production, consider prompting for
// this on very first boot instead of hardcoding it (e.g. a QR-code pairing
// flow), but a build-time constant is the simplest thing that works.
const SERVER_URL = 'https://testmobile.ijn.com.my';
const APP_VERSION = '1.0.0';
const DEFAULT_HOMEPAGE = 'https://example.com';

@Injectable({ providedIn: 'root' })
export class AppStateService {
  readonly ready = signal(false);
  readonly error = signal<string | null>(null);
  readonly step = signal('starting');
  readonly creds = signal<DeviceCreds | null>(null);
  readonly hostname = signal('');
  readonly homepage = signal(DEFAULT_HOMEPAGE);
  readonly settingsOpen = signal(false);
  readonly serverUrl = SERVER_URL;

  constructor(
    private storage: StorageService,
    private api: ApiService
  ) {
    this.api.configure(SERVER_URL);
  }

  async initialize(): Promise<void> {
    try {
      console.log('[kiosk] starting, server =', SERVER_URL);
      this.step.set('checking stored credentials');
      let existing = await this.storage.getCreds();
      let host = await this.storage.getHostname();
      console.log('[kiosk] stored creds:', existing, 'hostname:', host);

      if (!existing) {
        this.step.set('registering with server');
        host = await this.resolveDeviceHostname();
        const stableId = await this.resolveStableId();
        console.log('[kiosk] no stored creds — registering as', host, 'stableId:', stableId);
        existing = await this.api.register(host, APP_VERSION, stableId ?? undefined);
        console.log('[kiosk] register() succeeded:', existing);
        await this.storage.setCreds(existing);
        await this.storage.setHostname(host);
      }

      this.creds.set(existing);
      this.hostname.set(host ?? 'unknown-device');

      this.step.set('resolving homepage');
      const override = await this.storage.getUrlOverride();
      if (override) {
        console.log('[kiosk] using local URL override:', override);
        this.homepage.set(override);
      } else {
        try {
          this.step.set('fetching config from server');
          const config: DeviceConfig = await this.api.fetchMyConfig(existing);
          console.log('[kiosk] fetchMyConfig() succeeded:', config);
          if (config.homepage && config.homepage !== 'about:blank') {
            this.homepage.set(config.homepage);
          }
        } catch (cfgErr) {
          console.log('[kiosk] fetchMyConfig() FAILED, using default homepage:', cfgErr);
        }
      }
      this.step.set('done');
    } catch (err) {
      console.log('[kiosk] STARTUP FAILED:', err);
      this.error.set(err instanceof Error ? err.message : String(err));
    } finally {
      this.ready.set(true);
    }
  }

  async setUrlOverride(url: string | null): Promise<void> {
    await this.storage.setUrlOverride(url);
    this.homepage.set(url ?? DEFAULT_HOMEPAGE);
  }

  /**
   * Prefers the device's real, user-visible name (e.g. "asyhraf's S23
   * Ultra" — the same name shown for Bluetooth/Wi-Fi Direct pairing) over
   * a generated placeholder, since it makes devices instantly recognizable
   * in the admin device list instead of everything showing up as
   * "tv-ms7xyz". Falls back to a generated name if the native plugin is
   * unavailable (a plain browser, e.g. `ionic serve`) or returns nothing
   * usable.
   */
  private async resolveDeviceHostname(): Promise<string> {
    try {
      const { name } = await DeviceName.getName();
      if (name && name.trim().length > 0) {
        console.log('[kiosk] using real device name as hostname:', name);
        return name;
      }
    } catch (err) {
      console.log('[kiosk] DeviceName plugin unavailable, using generated hostname:', err);
    }
    return `tv-${Date.now().toString(36)}`;
  }

  /**
   * This is what makes re-registration idempotent: if the app's local
   * storage gets cleared or the app is reinstalled, sending the SAME
   * stable ID means the server reuses the existing device row (same
   * config, same history) instead of creating a duplicate that looks like
   * a brand-new, unconfigured kiosk.
   *
   * Uses Settings.Secure.ANDROID_ID, not a hardware MAC address — Android
   * 10+ blocks normal apps from reading the real Wi-Fi MAC (always returns
   * a dummy value), so ANDROID_ID is the practical, working alternative.
   * Returns null on a plain browser (no native plugin) or if unavailable
   * for any other reason — registration still works, it just won't
   * survive a data clear/reinstall in that case.
   */
  private async resolveStableId(): Promise<string | null> {
    try {
      const { id } = await DeviceName.getStableId();
      return id ?? null;
    } catch (err) {
      console.log('[kiosk] getStableId unavailable:', err);
      return null;
    }
  }

  openSettings(): void {
    this.settingsOpen.set(true);
  }

  closeSettings(): void {
    this.settingsOpen.set(false);
  }
}
