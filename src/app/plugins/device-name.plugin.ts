import { registerPlugin } from '@capacitor/core';

export interface DeviceNamePlugin {
  getName(): Promise<{ name: string }>;
  getStableId(): Promise<{ id: string | null }>;
}

/**
 * Matches android/app/src/main/java/com/kiosktvapp/DeviceNamePlugin.java —
 * both the plugin `name` here and the @CapacitorPlugin(name = "DeviceName")
 * annotation on the native side must match exactly.
 */
export const DeviceName = registerPlugin<DeviceNamePlugin>('DeviceName');
