// Mirrors kiosk-server/src/types.ts. Keep these in sync — they define the
// wire format between this app and the Fastify server.

export type Platform = 'windows' | 'android-tv' | 'google-tv' | 'android';

export interface DeviceCreds {
  deviceId: string;
  token: string;
}

export interface RegistrationResponse extends DeviceCreds {
  reused: boolean;
  config: DeviceConfig;
}

export interface HeartbeatPayload {
  sessionId: string;
  sessionStartedAt: string;
  cpu: number;
  ramUsedMb: number;
  ramTotalMb: number;
  diskUsedGb: number;
  diskTotalGb: number;
  currentUrl: string | null;
  uptimeSeconds: number;
  appVersion: string;
  networkType?: 'wifi' | 'ethernet' | 'unknown';
  ssid?: string | null;
  wifiPolicyStatus?: 'allowed' | 'blocked' | 'unknown' | 'not_wifi';
  networkState?: 'connected' | 'disconnected' | 'unknown';
  appState?: 'active' | 'background';
  lastDisconnectReason?: 'network_lost' | 'app_backgrounded' | null;
  clientSentAt?: string;
  sequence?: number;
}

export type CommandType =
  | 'open_url'
  | 'reload'
  | 'clear_cache'
  | 'screenshot'
  | 'restart_app'
  | 'reboot_device'
  | 'shutdown_device'
  | 'install_apk'
  | 'update_config'
  | 'play_sound';

export interface Command {
  id: string;
  deviceId: string;
  type: CommandType;
  payload: Record<string, unknown>;
  createdAt: string;
  deliveredAt: string | null;
  ackAt: string | null;
  status: 'queued' | 'delivered' | 'acked' | 'failed';
}

export interface DeviceConfig {
  homepage: string;
  reloadEverySeconds: number | null;
  allowNavigation: boolean;
  showCursor: boolean;
  takeScreenshotEverySeconds: number | null;
  autoUpdate: boolean;
  zoomLevel: number;
  allowedSsids: string[];
}

export type WSMessage =
  | { type: 'hello'; deviceId: string }
  | { type: 'command'; command: Command }
  | { type: 'command_ack'; commandId: string; status: 'acked' | 'failed'; message?: string }
  | { type: 'heartbeat'; payload: HeartbeatPayload }
  | { type: 'heartbeat_ack'; sequence?: number; serverTime: string; offlineAfterSeconds: number }
  | { type: 'ping'; nonce: string; serverTime: string }
  | { type: 'pong'; nonce?: string; clientTime?: string };
