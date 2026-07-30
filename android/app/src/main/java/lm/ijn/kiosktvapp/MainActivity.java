package lm.ijn.kiosktvapp;

import android.net.http.SslError;
import android.os.Bundle;
import android.webkit.SslErrorHandler;
import android.webkit.WebView;
import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;
import com.kiosktvapp.DeviceNamePlugin;
import com.kiosktvapp.KioskWebViewPlugin;
import com.kiosktvapp.WifiSettingsPlugin;

public class MainActivity extends BridgeActivity {

  @Override
  public void onCreate(Bundle savedInstanceState) {
    registerPlugin(WifiSettingsPlugin.class);
    registerPlugin(KioskWebViewPlugin.class);
    registerPlugin(DeviceNamePlugin.class);
    super.onCreate(savedInstanceState);

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
