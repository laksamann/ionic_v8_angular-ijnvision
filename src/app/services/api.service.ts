import { Injectable } from '@angular/core';
import { Capacitor } from '@capacitor/core';
import type {
  Command,
  DeviceCreds,
  HeartbeatPayload,
  DeviceConfig,
  Platform,
  RegistrationResponse,
} from '../models/types';

function detectPlatform(): Platform {
  // Google TV devices report as Android under the hood; there's no reliable
  // runtime signal to tell "Android TV" from "Google TV" apart from JS, so
  // default to android-tv and let the settings screen show the real model.
  return Capacitor.getPlatform() === 'android' ? 'android-tv' : 'android';
}

export interface PendingCommandsResponse {
  ok: true;
  pendingCommands: Command[];
  heartbeatAck: {
    sequence?: number;
    serverTime: string;
    offlineAfterSeconds: number;
  };
}

export interface DeviceConfigUpdateResponse {
  ok: true;
  config: DeviceConfig;
  updatedFields: Array<'homepage' | 'zoomLevel'>;
}

export interface DeviceConsoleEntry {
  level: string;
  message: string;
  source: string;
  line: number;
  timestamp: number;
}

@Injectable({ providedIn: 'root' })
export class ApiService {
  private base = '';

  configure(serverUrl: string): void {
    this.base = serverUrl.replace(/\/+$/, '');
  }

  async register(
    hostname: string,
    appVersion: string,
    enrollmentCode: string,
    mac?: string
  ): Promise<RegistrationResponse> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (enrollmentCode) headers['x-enrollment-code'] = enrollmentCode;

    const res = await fetch(`${this.base}/api/register`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ hostname, platform: detectPlatform(), appVersion, mac }),
    });
    if (!res.ok) throw await this.responseError('register', res);
    return res.json();
  }

  async heartbeat(
    creds: DeviceCreds,
    payload: HeartbeatPayload,
    keepalive = false
  ): Promise<PendingCommandsResponse> {
    const res = await fetch(`${this.base}/heartbeat`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${creds.token}`,
      },
      body: JSON.stringify(payload),
      keepalive,
    });
    if (!res.ok) throw await this.responseError('heartbeat', res);
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
    if (!res.ok) throw await this.responseError('ack', res);
  }

  async fetchMyConfig(creds: DeviceCreds): Promise<DeviceConfig> {
    const res = await fetch(`${this.base}/api/my/config`, {
      headers: { Authorization: `Bearer ${creds.token}` },
    });
    if (!res.ok) throw await this.responseError('fetchMyConfig', res);
    return res.json();
  }

  async updateMyConfig(
    creds: DeviceCreds,
    patch: Partial<Pick<DeviceConfig, 'homepage' | 'zoomLevel'>>
  ): Promise<DeviceConfigUpdateResponse> {
    const res = await fetch(`${this.base}/api/my/config`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${creds.token}`,
      },
      body: JSON.stringify(patch),
    });
    if (!res.ok) throw await this.responseError('updateMyConfig', res);
    return res.json();
  }

  /**
   * @param base64 raw base64 (no "data:image/jpeg;base64," prefix) — matches
   * what KioskWebViewPlugin.captureScreenshot() returns.
   */
  async uploadScreenshot(creds: DeviceCreds, base64: string): Promise<void> {
    const byteChars = atob(base64);
    const bytes = new Uint8Array(byteChars.length);
    for (let i = 0; i < byteChars.length; i++) {
      bytes[i] = byteChars.charCodeAt(i);
    }
    const blob = new Blob([bytes], { type: 'image/jpeg' });

    const formData = new FormData();
    formData.append('file', blob, 'screenshot.jpg');

    const res = await fetch(`${this.base}/uploadScreenshot`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${creds.token}` },
      // No Content-Type header set manually — the browser/WebView sets the
      // correct multipart boundary itself when the body is a FormData.
      body: formData,
    });
    if (!res.ok) throw await this.responseError('uploadScreenshot', res);
  }

  async uploadConsoleEntries(creds: DeviceCreds, entries: DeviceConsoleEntry[]): Promise<void> {
    if (!entries.length) return;
    const res = await fetch(`${this.base}/api/my/console`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${creds.token}`,
      },
      body: JSON.stringify({ entries }),
    });
    if (!res.ok) throw await this.responseError('uploadConsoleEntries', res);
  }

  apkDownloadUrl(downloadPath: string): string {
    return new URL(downloadPath, `${this.base}/`).toString();
  }

  wsUrl(creds: DeviceCreds): string {
    const wsBase = this.base.replace(/^http/, 'ws');
    return `${wsBase}/ws?token=${encodeURIComponent(creds.token)}`;
  }

  private async responseError(operation: string, response: Response): Promise<Error> {
    let detail = '';
    try {
      const body = (await response.json()) as { error?: string };
      detail = body.error ? `: ${body.error}` : '';
    } catch {
      // The status code is still useful when the response is not JSON.
    }
    return new Error(`${operation} failed (${response.status})${detail}`);
  }
}
