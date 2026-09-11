package lm.ijn.kiosktvapp;

import android.content.Context;
import android.net.http.SslError;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebView;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

public class MainActivity extends BridgeActivity {
  private WifiManager.WifiLock wifiLock;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    registerPlugin(com.kiosktvapp.WifiSettingsPlugin.class);
    registerPlugin(com.kiosktvapp.KioskWebViewPlugin.class);
    registerPlugin(com.kiosktvapp.DeviceNamePlugin.class);
    registerPlugin(com.kiosktvapp.DisplayModePlugin.class);
    registerPlugin(com.kiosktvapp.NetworkMonitorPlugin.class);
    registerPlugin(com.kiosktvapp.AppUpdatePlugin.class);
    super.onCreate(savedInstanceState);

    // Behave like a dedicated kiosk browser: draw edge-to-edge, hide both
    // Android system bars, and prevent the display from sleeping while the
    // assigned dashboard is running.
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    applyImmersiveMode();
    acquireWifiLock();

    // Internal-network-only deployment with a self-signed/internal-CA cert.
    // Accepting ALL SSL errors here covers page loads, fetch()/XHR, and the
    // WebSocket connection in one place, since the whole Capacitor app
    // (JS runtime included) runs inside this single WebView — unlike React
    // Native, which needed two separate native hooks for fetch vs WebSocket.
    //
    // DELIBERATE, ACCEPTED TRADE-OFF: only safe because this app talks
    // exclusively to servers on a closed internal hospital network. If this
    // app is ever pointed at a public-internet server, remove this and use
    // a proper Network Security Config with a pinned certificate instead.
    getBridge().getWebView().setWebViewClient(new TrustAllWebViewClient(getBridge()));
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus) applyImmersiveMode();
  }

  @Override
  public void onResume() {
    super.onResume();
    acquireWifiLock();
  }

  @Override
  public void onDestroy() {
    if (wifiLock != null && wifiLock.isHeld()) {
      wifiLock.release();
    }
    super.onDestroy();
  }

  /** Keeps Android's Wi-Fi radio out of power-save while this kiosk process
   * is alive. The lock is deliberately non-reference-counted so onResume()
   * can safely reassert it without accumulating unmatched acquisitions. */
  @SuppressWarnings("deprecation")
  private void acquireWifiLock() {
    try {
      if (wifiLock == null) {
        WifiManager wifiManager = (WifiManager) getApplicationContext()
          .getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) return;

        int lockMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
          ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
          : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
        wifiLock = wifiManager.createWifiLock(lockMode, "IJNVision:KioskWifiLock");
        wifiLock.setReferenceCounted(false);
      }

      if (!wifiLock.isHeld()) wifiLock.acquire();
    } catch (SecurityException error) {
      android.util.Log.w("IJNVision", "Could not acquire Wi-Fi keep-awake lock", error);
    }
  }

  private void applyImmersiveMode() {
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    WindowInsetsControllerCompat controller =
      WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
    controller.hide(WindowInsetsCompat.Type.systemBars());
    controller.setSystemBarsBehavior(
      WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    );
  }

  private static class TrustAllWebViewClient extends BridgeWebViewClient {
    TrustAllWebViewClient(Bridge bridge) {
      super(bridge);
    }

    @Override
    public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
      handler.proceed();
    }
  }
}
