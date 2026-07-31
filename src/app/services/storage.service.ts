import { Injectable } from '@angular/core';
import { Preferences } from '@capacitor/preferences';
import type { DeviceCreds } from '../models/types';

const KEYS = {
  serverUrl: 'kiosk:serverUrl',
  creds: 'kiosk:deviceCreds',
  urlOverride: 'kiosk:urlOverride',
  hostname: 'kiosk:hostname',
  displayModeId: 'kiosk:displayModeId',
  zoomOverride: 'kiosk:zoomOverride',
  rotationDegrees: 'kiosk:rotationDegrees',
} as const;

@Injectable({ providedIn: 'root' })
export class StorageService {
  async getServerUrl(): Promise<string | null> {
    const { value } = await Preferences.get({ key: KEYS.serverUrl });
    return value;
  }
  async setServerUrl(url: string): Promise<void> {
    await Preferences.set({ key: KEYS.serverUrl, value: url });
  }

  async getCreds(): Promise<DeviceCreds | null> {
    const { value } = await Preferences.get({ key: KEYS.creds });
    return value ? JSON.parse(value) : null;
  }
  async setCreds(creds: DeviceCreds): Promise<void> {
    await Preferences.set({ key: KEYS.creds, value: JSON.stringify(creds) });
  }
  async clearCreds(): Promise<void> {
    await Preferences.remove({ key: KEYS.creds });
  }

  /** A manually-set URL on the settings screen wins over the server-assigned homepage until cleared. */
  async getUrlOverride(): Promise<string | null> {
    const { value } = await Preferences.get({ key: KEYS.urlOverride });
    return value;
  }
  async setUrlOverride(url: string | null): Promise<void> {
    if (url) await Preferences.set({ key: KEYS.urlOverride, value: url });
    else await Preferences.remove({ key: KEYS.urlOverride });
  }

  async getHostname(): Promise<string | null> {
    const { value } = await Preferences.get({ key: KEYS.hostname });
    return value;
  }
  async setHostname(hostname: string): Promise<void> {
    await Preferences.set({ key: KEYS.hostname, value: hostname });
  }

  /** A manually-picked display mode (from the settings screen) wins over
   * auto-selecting the highest available resolution, until cleared. */
  async getDisplayModeId(): Promise<number | null> {
    const { value } = await Preferences.get({ key: KEYS.displayModeId });
    return value ? Number(value) : null;
  }
  async setDisplayModeId(modeId: number | null): Promise<void> {
    if (modeId !== null) await Preferences.set({ key: KEYS.displayModeId, value: String(modeId) });
    else await Preferences.remove({ key: KEYS.displayModeId });
  }

  /** A manually-set zoom level (from the settings screen) wins over the
   * server-assigned zoomLevel config, until cleared — same pattern as
   * the URL override above. */
  async getZoomOverride(): Promise<number | null> {
    const { value } = await Preferences.get({ key: KEYS.zoomOverride });
    return value ? Number(value) : null;
  }
  async setZoomOverride(percent: number | null): Promise<void> {
    if (percent !== null) await Preferences.set({ key: KEYS.zoomOverride, value: String(percent) });
    else await Preferences.remove({ key: KEYS.zoomOverride });
  }

  /** Manual content rotation (0/90/180/270) — persisted so it survives
   * restarts, since it's set once for however the device is physically
   * mounted, not something expected to change often. */
  async getRotationDegrees(): Promise<number> {
    const { value } = await Preferences.get({ key: KEYS.rotationDegrees });
    return value ? Number(value) : 0;
  }
  async setRotationDegrees(degrees: number): Promise<void> {
    await Preferences.set({ key: KEYS.rotationDegrees, value: String(degrees) });
  }
}