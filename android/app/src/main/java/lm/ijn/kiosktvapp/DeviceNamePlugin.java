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
/**
 * Reads two different device-identity signals:
 *
 * - getName(): the user-visible device name (Settings.Global.DEVICE_NAME) —
 *   for display purposes only, NOT unique/stable enough to key a database
 *   row on (users can rename it, and it's not guaranteed unique across
 *   units of the same model).
 *
 * - getStableId(): Settings.Secure.ANDROID_ID — survives app reinstalls and
 *   data clears (changes only on factory reset), needs no special
 *   permission. This is the practical alternative to a hardware MAC
 *   address: since Android 10, WifiManager.getConnectionInfo().getMacAddress()
 *   always returns a dummy "02:00:00:00:00:00" for any app that isn't a
 *   Device Owner or system app — reading it at all would silently produce
 *   the same fake value for every device, which is worse than not having
 *   an identifier. ANDROID_ID is Android's own documented recommendation
 *   for this exact use case.
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

  @PluginMethod
  public void getStableId(PluginCall call) {
    String id = Settings.Secure.getString(getContext().getContentResolver(), Settings.Secure.ANDROID_ID);

    JSObject result = new JSObject();
    result.put("id", id); // null in the unlikely case the setting is unavailable
    call.resolve(result);
  }
}
