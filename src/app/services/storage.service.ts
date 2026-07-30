import { Injectable } from '@angular/core';
import { Preferences } from '@capacitor/preferences';
import type { DeviceCreds } from '../models/types';

const KEYS = {
  serverUrl: 'kiosk:serverUrl',
  creds: 'kiosk:deviceCreds',
  urlOverride: 'kiosk:urlOverride',
  hostname: 'kiosk:hostname',
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
}
