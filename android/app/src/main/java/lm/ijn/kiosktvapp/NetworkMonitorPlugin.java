package com.kiosktvapp;

import android.Manifest;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
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
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;

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
    result.put("interfaces", readNetworkInterfaces());
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
    result.put("interfaces", readNetworkInterfaces());
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

  /**
   * Reports every non-loopback interface exposed by Android instead of
   * guessing one device IP or treating the stable Android ID as a MAC.
   * A TV can have wlan0 and eth0 at the same time, and VPN/USB adapters can
   * add more entries, so this deliberately returns a structured array.
   */
  private JSArray readNetworkInterfaces() {
    JSArray result = new JSArray();
    Set<String> activeNames = new HashSet<>();

    try {
      if (connectivityManager != null) {
        for (Network network : connectivityManager.getAllNetworks()) {
          LinkProperties properties = connectivityManager.getLinkProperties(network);
          if (properties != null && properties.getInterfaceName() != null) {
            activeNames.add(properties.getInterfaceName());
          }
        }
      }

      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      if (interfaces == null) return result;
      while (interfaces.hasMoreElements()) {
        NetworkInterface networkInterface = interfaces.nextElement();
        if (networkInterface.isLoopback()) continue;

        JSArray ipv4 = new JSArray();
        JSArray ipv6 = new JSArray();
        Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
        while (addresses.hasMoreElements()) {
          InetAddress address = addresses.nextElement();
          if (address.isLoopbackAddress()) continue;
          if (address instanceof Inet4Address) ipv4.put(address.getHostAddress());
          else if (address instanceof Inet6Address) ipv6.put(address.getHostAddress());
        }

        byte[] hardware = networkInterface.getHardwareAddress();
        String macAddress = formatMacAddress(hardware);
        // Omit empty software placeholders, but retain down physical
        // interfaces when Android exposes a MAC so wired + Wi-Fi are visible.
        if (ipv4.length() == 0 && ipv6.length() == 0 && macAddress == null) continue;

        JSObject item = new JSObject();
        item.put("name", networkInterface.getName());
        item.put("displayName", networkInterface.getDisplayName());
        item.put("type", interfaceType(networkInterface.getName()));
        item.put("active", activeNames.contains(networkInterface.getName()));
        item.put("up", networkInterface.isUp());
        item.put("virtual", networkInterface.isVirtual());
        item.put("macAddress", macAddress);
        item.put("ipv4Addresses", ipv4);
        item.put("ipv6Addresses", ipv6);
        result.put(item);
      }
    } catch (Exception ignored) {
      // Returning the interfaces collected so far is safer than failing the
      // entire heartbeat on vendor-specific Android networking restrictions.
    }
    return result;
  }

  private String formatMacAddress(byte[] hardware) {
    if (hardware == null || hardware.length == 0) return null;
    StringBuilder value = new StringBuilder();
    for (byte part : hardware) {
      if (value.length() > 0) value.append(':');
      value.append(String.format(java.util.Locale.US, "%02X", part & 0xff));
    }
    String mac = value.toString();
    if ("00:00:00:00:00:00".equals(mac) || "02:00:00:00:00:00".equals(mac)) return null;
    return mac;
  }

  private String interfaceType(String name) {
    String normalized = name == null ? "" : name.toLowerCase(java.util.Locale.US);
    if (normalized.startsWith("wlan") || normalized.startsWith("wifi")) return "wifi";
    if (normalized.startsWith("eth") || normalized.startsWith("en")) return "ethernet";
    if (normalized.startsWith("rmnet") || normalized.startsWith("ccmni") || normalized.startsWith("pdp")) return "cellular";
    if (normalized.startsWith("tun") || normalized.startsWith("tap") || normalized.startsWith("ppp")) return "vpn";
    return "other";
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
