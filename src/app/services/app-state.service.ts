import { Injectable, signal } from '@angular/core';
import { StorageService } from './storage.service';
import { ApiService } from './api.service';
import { DeviceName } from '../plugins/device-name.plugin';
import { DisplayMode } from '../plugins/display-mode.plugin';
import type { DeviceCreds, DeviceConfig } from '../models/types';
import { environment } from '../../environments/environment';

// Point this at your Fastify server. In production, consider prompting for
// this on very first boot instead of hardcoding it (e.g. a QR-code pairing
// flow), but a build-time constant is the simplest thing that works.
const DEFAULT_HOMEPAGE = 'https://example.com';
const DEFAULT_ZOOM = 100;
const DEFAULT_CONFIG: DeviceConfig = {
  homepage: DEFAULT_HOMEPAGE,
  reloadEverySeconds: null,
  allowNavigation: false,
  showCursor: false,
  takeScreenshotEverySeconds: null,
  autoUpdate: true,
  zoomLevel: DEFAULT_ZOOM,
};

@Injectable({ providedIn: 'root' })
export class AppStateService {
  readonly ready = signal(false);
  readonly error = signal<string | null>(null);
  readonly step = signal('starting');
  readonly creds = signal<DeviceCreds | null>(null);
  readonly hostname = signal('');
  readonly homepage = signal(DEFAULT_HOMEPAGE);
  readonly zoomLevel = signal(DEFAULT_ZOOM);
  readonly remoteConfig = signal<DeviceConfig>(DEFAULT_CONFIG);
  readonly settingsOpen = signal(false);
  readonly serverUrl = environment.kioskServerUrl.replace(/\/+$/, '');

  constructor(
    private storage: StorageService,
    private api: ApiService
  ) {
    this.api.configure(this.serverUrl);
  }

  async initialize(): Promise<void> {
    try {
      console.log('[kiosk] starting, server =', this.serverUrl);
      await this.applyDisplayMode();

      this.step.set('checking stored credentials');
      let existing = await this.storage.getCreds();
      let host = await this.storage.getHostname();
      console.log('[kiosk] stored creds:', existing, 'hostname:', host);

      let config = (await this.storage.getLastRemoteConfig()) ?? DEFAULT_CONFIG;

      if (!existing) {
        this.step.set('registering with server');
        host = await this.resolveDeviceHostname();
        const stableId = await this.resolveStableId();
        console.log('[kiosk] no stored creds — registering as', host, 'stableId:', stableId);
        const registration = await this.api.register(
          host,
          environment.appVersion,
          environment.enrollmentCode,
          stableId ?? undefined
        );
        console.log('[kiosk] register() succeeded; reused =', registration.reused);
        existing = { deviceId: registration.deviceId, token: registration.token };
        config = registration.config ?? config;
        await this.storage.setCreds(existing);
        await this.storage.setHostname(host);
      }

      this.creds.set(existing);
      this.hostname.set(host ?? 'unknown-device');

      try {
        this.step.set('fetching config from server');
        config = await this.api.fetchMyConfig(existing);
        console.log('[kiosk] fetchMyConfig() succeeded:', config);
      } catch (cfgErr) {
        console.log('[kiosk] fetchMyConfig() FAILED, using last saved config:', cfgErr);
      }

      // Migrate installations from the old local-override model. The server
      // config is now authoritative in both directions.
      await Promise.all([
        this.storage.setUrlOverride(null),
        this.storage.setZoomOverride(null),
      ]);
      await this.applyRemoteConfig(config);
      this.step.set('done');
    } catch (err) {
      console.log('[kiosk] STARTUP FAILED:', err);
      this.error.set(err instanceof Error ? err.message : String(err));
    } finally {
      this.ready.set(true);
    }
  }

  /** Saves the user-facing settings to the server and MySQL. Clearing the
   * legacy local override keys makes the returned server config authoritative
   * immediately and on every future launch. */
  async updateConfigFromDevice(
    patch: Partial<Pick<DeviceConfig, 'homepage' | 'zoomLevel'>>
  ): Promise<DeviceConfig> {
    const creds = this.creds();
    if (!creds) throw new Error('device is not registered');

    const result = await this.api.updateMyConfig(creds, patch);
    const clearLegacyOverrides: Promise<void>[] = [];
    if ('homepage' in patch) clearLegacyOverrides.push(this.storage.setUrlOverride(null));
    if ('zoomLevel' in patch) clearLegacyOverrides.push(this.storage.setZoomOverride(null));
    await Promise.all(clearLegacyOverrides);
    await this.applyRemoteConfig(result.config);
    return this.remoteConfig();
  }

  async syncRemoteConfig(): Promise<DeviceConfig> {
    const creds = this.creds();
    if (!creds) throw new Error('device is not registered');
    const config = await this.api.fetchMyConfig(creds);
    await this.applyRemoteConfig(config);
    return this.remoteConfig();
  }

  async applyRemoteConfig(config: Partial<DeviceConfig>): Promise<void> {
    const normalized = this.normalizeRemoteConfig(config);
    this.remoteConfig.set(normalized);
    await this.storage.setLastRemoteConfig(normalized);

    this.homepage.set(normalized.homepage);
    this.zoomLevel.set(normalized.zoomLevel);
  }

  private normalizeRemoteConfig(config: Partial<DeviceConfig>): DeviceConfig {
    const candidate = { ...DEFAULT_CONFIG, ...config };
    let homepage = DEFAULT_HOMEPAGE;
    try {
      const parsed = new URL(candidate.homepage);
      if (parsed.protocol === 'http:' || parsed.protocol === 'https:') homepage = parsed.toString();
    } catch {
      console.log('[kiosk] ignoring invalid remote homepage:', candidate.homepage);
    }

    const interval = (value: unknown, minimum: number): number | null =>
      Number.isInteger(value) && (value as number) >= minimum && (value as number) <= 86_400
        ? (value as number)
        : null;

    const zoomLevel =
      Number.isInteger(candidate.zoomLevel) && candidate.zoomLevel >= 25 && candidate.zoomLevel <= 500
        ? candidate.zoomLevel
        : DEFAULT_ZOOM;

    return {
      homepage,
      reloadEverySeconds: interval(candidate.reloadEverySeconds, 10),
      allowNavigation:
        typeof candidate.allowNavigation === 'boolean'
          ? candidate.allowNavigation
          : DEFAULT_CONFIG.allowNavigation,
      showCursor:
        typeof candidate.showCursor === 'boolean' ? candidate.showCursor : DEFAULT_CONFIG.showCursor,
      takeScreenshotEverySeconds: interval(candidate.takeScreenshotEverySeconds, 30),
      autoUpdate:
        typeof candidate.autoUpdate === 'boolean' ? candidate.autoUpdate : DEFAULT_CONFIG.autoUpdate,
      zoomLevel,
    };
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

  /**
   * Applies either a manually-pinned display mode (set from the settings
   * screen) or auto-selects the highest resolution the connected TV
   * reports supporting over HDMI. Runs once at boot — this plugin call is
   * Android-only, so it silently no-ops on a plain browser.
   */
  private async applyDisplayMode(): Promise<void> {
    try {
      const pinnedModeId = await this.storage.getDisplayModeId();
      if (pinnedModeId !== null) {
        await DisplayMode.setMode({ modeId: pinnedModeId });
        console.log('[kiosk] applied pinned display mode:', pinnedModeId);
      } else {
        const result = await DisplayMode.setHighestResolution();
        console.log('[kiosk] auto-selected highest display mode:', result);
      }
    } catch (err) {
      console.log('[kiosk] DisplayMode plugin unavailable (expected in a browser):', err);
    }
  }

  openSettings(): void {
    this.settingsOpen.set(true);
  }

  closeSettings(): void {
    this.settingsOpen.set(false);
  }
}
