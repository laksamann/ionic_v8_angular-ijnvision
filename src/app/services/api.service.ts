import { Injectable } from '@angular/core';
import { Capacitor } from '@capacitor/core';
import type { DeviceCreds, HeartbeatPayload, DeviceConfig, Platform } from '../models/types';

function detectPlatform(): Platform {
  // Google TV devices report as Android under the hood; there's no reliable
  // runtime signal to tell "Android TV" from "Google TV" apart from JS, so
  // default to android-tv and let the settings screen show the real model.
  return Capacitor.getPlatform() === 'android' ? 'android-tv' : 'android';
}

export interface PendingCommandsResponse {
  ok: true;
  pendingCommands: unknown[];
}

@Injectable({ providedIn: 'root' })
export class ApiService {
  private base = '';

  configure(serverUrl: string): void {
    this.base = serverUrl.replace(/\/+$/, '');
  }

  async register(hostname: string, appVersion: string, mac?: string): Promise<DeviceCreds> {
    const res = await fetch(`${this.base}/api/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ hostname, platform: detectPlatform(), appVersion, mac }),
    });
    if (!res.ok) throw new Error(`register failed: ${res.status}`);
    return res.json();
  }

  async heartbeat(creds: DeviceCreds, payload: HeartbeatPayload): Promise<PendingCommandsResponse> {
    const res = await fetch(`${this.base}/heartbeat`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${creds.token}`,
      },
      body: JSON.stringify(payload),
    });
    if (!res.ok) throw new Error(`heartbeat failed: ${res.status}`);
    return res.json();
  }

  async ackCommand(
    creds: DeviceCreds,
    commandId: string,
    status: 'acked' | 'failed',
    message?: string
  ): Promise<void> {
    const res = await fetch(`${this.base}/api/commands/${commandId}/ack`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${creds.token}`,
      },
      body: JSON.stringify({ status, message }),
    });
    if (!res.ok) throw new Error(`ack failed: ${res.status}`);
  }

  async fetchMyConfig(creds: DeviceCreds): Promise<DeviceConfig> {
    const res = await fetch(`${this.base}/api/my/config`, {
      headers: { Authorization: `Bearer ${creds.token}` },
    });
    if (!res.ok) throw new Error(`fetchMyConfig failed: ${res.status}`);
    return res.json();
  }

  wsUrl(creds: DeviceCreds): string {
    const wsBase = this.base.replace(/^http/, 'ws');
    return `${wsBase}/ws?token=${encodeURIComponent(creds.token)}`;
  }
}
