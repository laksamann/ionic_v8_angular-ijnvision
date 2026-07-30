import { registerPlugin } from '@capacitor/core';

export interface WifiSettingsPlugin {
  openSettings(): Promise<void>;
}

/**
 * Matches android/app/src/main/java/com/kiosktvapp/WifiSettingsPlugin.java —
 * both the plugin `name` here and the @CapacitorPlugin(name = "WifiSettings")
 * annotation on the native side must match exactly.
 */
export const WifiSettings = registerPlugin<WifiSettingsPlugin>('WifiSettings');
