package com.kiosktvapp;

import android.Manifest;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.widget.Toast;
import androidx.annotation.NonNull;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.util.ArrayList;
import java.util.List;

@CapacitorPlugin(
  name = "NetworkMonitor",
  permissions = {
    @Permission(alias = "location", strings = { Manifest.permission.ACCESS_FINE_LOCATION }),
    @Permission(alias = "nearbyWifi", strings = { Manifest.permission.NEARBY_WIFI_DEVICES })
  }
)
public class NetworkMonitorPlugin extends Plugin {
  private final List<String> allowedSsids = new ArrayList<>();
  private ConnectivityManager connectivityManager;
  private ConnectivityManager.NetworkCallback networkCallback;
  private String lastSignature = "";
  // Android may redact WifiInfo as soon as the activity is backgrounded.
  // Keep the last verified SSID while the same Wi-Fi transport is still
  // connected; clear it only after a real network loss or transport change.
  private String lastKnownSsid = null;

  @PluginMethod
  public void startMonitoring(PluginCall call) {
    readAllowedSsids(call);
    boolean locationMissing = getPermissionState("location") != PermissionState.GRANTED;
    boolean nearbyMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
      && getPermissionState("nearbyWifi") != PermissionState.GRANTED;
    if (locationMissing || nearbyMissing) {
      requestPermissionForAliases(
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
          ? new String[] { "location", "nearbyWifi" }
          : new String[] { "location" },
        call,
        "permissionResult"
      );
      return;
    }
    startCallback();
    call.resolve(readStatus());
  }

  @PermissionCallback
  private void permissionResult(PluginCall call) {
    startCallback();
    JSObject status = readStatus();
    status.put("permissionGranted",
      getPermissionState("location") == PermissionState.GRANTED
        && (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
          || getPermissionState("nearbyWifi") == PermissionState.GRANTED)
    );
    call.resolve(status);
  }

  @PluginMethod
  public void updatePolicy(PluginCall call) {
    readAllowedSsids(call);
    JSObject status = readStatus();
    publish(status, true);
    call.resolve(status);
  }

  @PluginMethod
  public void getStatus(PluginCall call) {
    call.resolve(readStatus());
  }

  @PluginMethod
  public void showAlert(PluginCall call) {
    String message = call.getString("message", "IJN Vision status changed");
    getActivity().runOnUiThread(() -> Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show());
    call.resolve();
  }

  private void readAllowedSsids(PluginCall call) {
    allowedSsids.clear();
    JSArray values = call.getArray("allowedSsids");
    if (values == null) return;
    for (int i = 0; i < values.length(); i++) {
      String value = values.optString(i, "").trim();
      if (!value.isEmpty() && !allowedSsids.contains(value)) allowedSsids.add(value);
    }
  }

  private void startCallback() {
    if (networkCallback != null) return;
    connectivityManager = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
    if (connectivityManager == null) return;

    networkCallback = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
      ? new ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
          @Override public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities caps) { publish(readStatus(), false); }
          @Override public void onLost(@NonNull Network network) { publish(disconnectedStatus(), false); }
        }
      : new ConnectivityManager.NetworkCallback() {
          @Override public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities caps) { publish(readStatus(), false); }
          @Override public void onLost(@NonNull Network network) { publish(disconnectedStatus(), false); }
        };
    connectivityManager.registerDefaultNetworkCallback(networkCallback);
    publish(readStatus(), false);
  }

  @SuppressWarnings("deprecation")
  private JSObject readStatus() {
    JSObject result = new JSObject();
    Network active = connectivityManager == null ? null : connectivityManager.getActiveNetwork();
    NetworkCapabilities caps = active == null || connectivityManager == null
      ? null
      : connectivityManager.getNetworkCapabilities(active);
    boolean connected = active != null && caps != null;
    boolean wifi = connected && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    boolean ethernet = connected && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
    String ssid = null;

    if (wifi) {
      WifiInfo info = null;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && caps.getTransportInfo() instanceof WifiInfo) {
        info = (WifiInfo) caps.getTransportInfo();
      }
      if (info == null) {
        WifiManager manager = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (manager != null) info = manager.getConnectionInfo();
      }
      if (info != null) ssid = cleanSsid(info.getSSID());
      if (ssid != null) lastKnownSsid = ssid;
      else ssid = lastKnownSsid;
    } else {
      lastKnownSsid = null;
    }

    String type = wifi ? "wifi" : (ethernet ? "ethernet" : "unknown");
    String policy = !wifi ? "not_wifi" : policyFor(ssid);
    result.put("connected", connected);
    result.put("networkState", connected ? "connected" : "disconnected");
    result.put("networkType", type);
    result.put("ssid", ssid);
    result.put("wifiPolicyStatus", policy);
    result.put("allowedSsids", new JSArray(allowedSsids));
    result.put("occurredAt", System.currentTimeMillis());
    return result;
  }

  private JSObject disconnectedStatus() {
    lastKnownSsid = null;
    JSObject result = new JSObject();
    result.put("connected", false);
    result.put("networkState", "disconnected");
    result.put("networkType", "unknown");
    result.put("ssid", (String) null);
    result.put("wifiPolicyStatus", "unknown");
    result.put("allowedSsids", new JSArray(allowedSsids));
    result.put("occurredAt", System.currentTimeMillis());
    return result;
  }

  private String policyFor(String ssid) {
    if (allowedSsids.isEmpty()) return "allowed";
    if (ssid == null) return "unknown";
    return allowedSsids.contains(ssid) ? "allowed" : "blocked";
  }

  private String cleanSsid(String value) {
    if (value == null || WifiManager.UNKNOWN_SSID.equals(value)) return null;
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  private void publish(JSObject status, boolean force) {
    String signature = status.optString("networkState") + "|" + status.optString("networkType") + "|"
      + status.optString("ssid") + "|" + status.optString("wifiPolicyStatus");
    if (!force && signature.equals(lastSignature)) return;
    boolean hadPrevious = !lastSignature.isEmpty();
    lastSignature = signature;
    notifyListeners("networkStatusChanged", status, true);
    if (!hadPrevious) return;

    getActivity().runOnUiThread(() -> {
      String state = status.optString("networkState");
      String policy = status.optString("wifiPolicyStatus");
      String ssid = status.optString("ssid", "Unknown Wi-Fi");
      String message;
      if ("disconnected".equals(state)) message = "IJN Vision alert: network connection lost";
      else if ("blocked".equals(policy)) message = "IJN Vision alert: wrong Wi-Fi " + ssid + ". Allowed: " + String.join(", ", allowedSsids);
      else if ("unknown".equals(policy) && "wifi".equals(status.optString("networkType"))) message = "IJN Vision alert: Wi-Fi name unavailable. Grant Nearby devices/location permission.";
      else message = "IJN Vision network restored: " + ("unknown".equals(status.optString("networkType")) ? "connected" : ssid);
      Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
    });
  }

  @Override
  protected void handleOnDestroy() {
    if (connectivityManager != null && networkCallback != null) {
      try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) { }
    }
    networkCallback = null;
  }
}
