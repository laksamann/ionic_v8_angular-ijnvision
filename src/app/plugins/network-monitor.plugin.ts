import { registerPlugin } from '@capacitor/core';
import type { PluginListenerHandle } from '@capacitor/core';

export interface NetworkStatus {
  connected: boolean;
  networkState: 'connected' | 'disconnected' | 'unknown';
  networkType: 'wifi' | 'ethernet' | 'unknown';
  ssid: string | null;
  wifiPolicyStatus: 'allowed' | 'blocked' | 'unknown' | 'not_wifi';
  allowedSsids: string[];
  occurredAt: number;
  permissionGranted?: boolean;
}

export interface NetworkMonitorPlugin {
  startMonitoring(options: { allowedSsids: string[] }): Promise<NetworkStatus>;
  updatePolicy(options: { allowedSsids: string[] }): Promise<NetworkStatus>;
  getStatus(): Promise<NetworkStatus>;
  showAlert(options: { message: string }): Promise<void>;
  addListener(
    eventName: 'networkStatusChanged',
    listenerFunc: (status: NetworkStatus) => void
  ): Promise<PluginListenerHandle>;
}

export const NetworkMonitor = registerPlugin<NetworkMonitorPlugin>('NetworkMonitor');
