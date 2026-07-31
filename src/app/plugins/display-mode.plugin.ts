import { registerPlugin } from '@capacitor/core';

export interface DisplayModeInfo {
  modeId: number;
  width: number;
  height: number;
  refreshRate: number;
  isCurrent?: boolean;
}

export interface DisplayModePlugin {
  listModes(): Promise<{ modes: DisplayModeInfo[] }>;
  setMode(options: { modeId: number }): Promise<void>;
  setHighestResolution(): Promise<DisplayModeInfo>;
}

/**
 * Matches android/app/src/main/java/com/kiosktvapp/DisplayModePlugin.java —
 * both the plugin `name` here and the @CapacitorPlugin(name = "DisplayMode")
 * annotation on the native side must match exactly.
 *
 * "Supported modes" come from the physical display connected over HDMI
 * (via EDID) — this can only pick among resolutions the TV itself reports
 * supporting, not force an arbitrary one.
 */
export const DisplayMode = registerPlugin<DisplayModePlugin>('DisplayMode');
