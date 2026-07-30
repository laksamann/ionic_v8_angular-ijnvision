package com.kiosktvapp;

import android.content.Intent;
import android.provider.Settings;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Small custom plugin so the settings screen can jump straight to Android's
 * Wi-Fi settings panel. Capacitor core has no built-in API for this — it's a
 * one-method plugin, same pattern as any built-in Capacitor plugin.
 */
@CapacitorPlugin(name = "WifiSettings")
public class WifiSettingsPlugin extends Plugin {

  @PluginMethod
  public void openSettings(PluginCall call) {
    Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    getContext().startActivity(intent);
    call.resolve();
  }
}
