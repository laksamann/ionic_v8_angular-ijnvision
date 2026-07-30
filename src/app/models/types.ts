// Mirrors kiosk-server/src/types.ts. Keep these in sync — they define the
// wire format between this app and the Fastify server.

export type Platform = 'windows' | 'android-tv' | 'google-tv' | 'android';

export interface DeviceCreds {
  deviceId: string;
  token: string;
}

export interface HeartbeatPayload {
  cpu: number;
  ramUsedMb: number;
  ramTotalMb: number;
  diskUsedGb: number;
  diskTotalGb: number;
  currentUrl: string | null;
  uptimeSeconds: number;
  appVersion: string;
  networkType?: 'wifi' | 'ethernet' | 'unknown';
}

export type CommandType =
  | 'open_url'
  | 'reload'
  | 'clear_cache'
  | 'screenshot'
  | 'restart_app'
  | 'reboot_device'
  | 'shutdown_device'
  | 'update_config'
  | 'play_sound';

export interface Command {
  id: string;
  deviceId: string;
  type: CommandType;
  payload: Record<string, unknown>;
  createdAt: string;
  status: 'queued' | 'delivered' | 'acked' | 'failed';
}

export interface DeviceConfig {
  homepage: string;
  reloadEverySeconds: number | null;
  allowNavigation: boolean;
  showCursor: boolean;
  takeScreenshotEverySeconds: number | null;
  autoUpdate: boolean;
}

export type WSMessage =
  | { type: 'hello'; deviceId: string }
  | { type: 'command'; command: Command }
  | { type: 'command_ack'; commandId: string; status: 'acked' | 'failed'; message?: string }
  | { type: 'heartbeat'; payload: HeartbeatPayload }
  | { type: 'pong' };
