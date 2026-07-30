package com.kiosktvapp;

import android.os.Build;
import android.provider.Settings;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Reads the device's user-visible name (Settings.Global.DEVICE_NAME) — the
 * same name shown for Bluetooth/Wi-Fi Direct pairing and editable under
 * Settings > About > Device name (e.g. "asyhraf's S23 Ultra"). No standard
 * Capacitor plugin exposes this, and it requires no special permission to
 * read (unlike the Bluetooth adapter's name, which needs BLUETOOTH_CONNECT
 * on API 31+ — deliberately avoided here to skip a runtime permission
 * prompt on a kiosk device).
 */
@CapacitorPlugin(name = "DeviceName")
public class DeviceNamePlugin extends Plugin {

  @PluginMethod
  public void getName(PluginCall call) {
    String name = null;
    try {
      name = Settings.Global.getString(getContext().getContentResolver(), Settings.Global.DEVICE_NAME);
    } catch (Exception e) {
      // Setting doesn't exist on this OS version — fall through to the model name below.
    }

    if (name == null || name.trim().isEmpty()) {
      name = Build.MODEL; // e.g. "SM-S928B" — always present, worst-case fallback
    }

    JSObject result = new JSObject();
    result.put("name", name);
    call.resolve(result);
  }
}
