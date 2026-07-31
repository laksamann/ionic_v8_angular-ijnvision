package com.kiosktvapp;

import android.net.http.SslError;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.SslErrorHandler;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import java.io.ByteArrayOutputStream;
import android.util.Base64;

/**
 * Manages a second, separate Android WebView layered on top of Capacitor's
 * own WebView, used to display the kiosk's assigned URL as a genuine
 * top-level page rather than nesting it in an <iframe> inside the Angular
 * app. This matters because many sites (Google, YouTube, Facebook, etc.)
 * send X-Frame-Options / CSP frame-ancestors specifically to refuse being
 * embedded in someone else's page — an iframe cannot bypass that, but a
 * WebView navigating directly to the URL (same as react-native-webview
 * does) is not framing anything, so it's unaffected.
 *
 * show() brings this WebView to the front, covering the Capacitor WebView.
 * hide() sends it away so the Angular UI underneath (settings screen, boot
 * screen, error screen) becomes visible again.
 */
@CapacitorPlugin(name = "KioskWebView")
public class KioskWebViewPlugin extends Plugin {

  private WebView kioskWebView;

  private WebView ensureWebView() {
    if (kioskWebView == null) {
      kioskWebView = new WebView(getContext());
      kioskWebView.setBackgroundColor(android.graphics.Color.BLACK);
      kioskWebView.getSettings().setJavaScriptEnabled(true);
      kioskWebView.getSettings().setDomStorageEnabled(true);
      kioskWebView.getSettings().setMediaPlaybackRequiresUserGesture(false);
      kioskWebView.setWebViewClient(new TrustAllClient());

      ViewGroup.LayoutParams params =
        new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
      getActivity().addContentView(kioskWebView, params);
      kioskWebView.setVisibility(View.GONE);
    }
    return kioskWebView;
  }

  @PluginMethod
  public void show(PluginCall call) {
    String url = call.getString("url");
    getActivity().runOnUiThread(() -> {
      WebView wv = ensureWebView();
      if (url != null && !url.isEmpty()) {
        wv.loadUrl(url);
      }
      wv.setVisibility(View.VISIBLE);
      wv.bringToFront();
    });
    call.resolve();
  }

  @PluginMethod
  public void hide(PluginCall call) {
    getActivity().runOnUiThread(() -> {
      if (kioskWebView != null) kioskWebView.setVisibility(View.GONE);
    });
    call.resolve();
  }

  @PluginMethod
  public void reload(PluginCall call) {
    getActivity().runOnUiThread(() -> {
      if (kioskWebView != null) kioskWebView.reload();
    });
    call.resolve();
  }

  @PluginMethod
  public void clearCacheAndReload(PluginCall call) {
    getActivity().runOnUiThread(() -> {
      if (kioskWebView != null) {
        kioskWebView.clearCache(true);
        kioskWebView.reload();
      }
    });
    call.resolve();
  }

  /**
   * Captures the kiosk WebView's currently rendered content as a JPEG,
   * returned as base64 (kept in memory, never written to disk here — the
   * JS side uploads it directly to kiosk-server via multipart/form-data).
   *
   * Uses view.draw(canvas) rather than the newer PixelCopy API — simpler,
   * and WebView (unlike GLSurfaceView/hardware-accelerated video views)
   * composites normally into the view hierarchy, so draw()-based capture
   * works correctly here. If screenshots ever come out blank on a specific
   * device/OS version, PixelCopy is the documented fallback for
   * hardware-accelerated capture.
   */
  @PluginMethod
  public void captureScreenshot(PluginCall call) {
    getActivity().runOnUiThread(() -> {
      if (kioskWebView == null || kioskWebView.getWidth() == 0 || kioskWebView.getHeight() == 0) {
        call.reject("kiosk WebView not ready — nothing visible to capture yet");
        return;
      }

      try {
        Bitmap bitmap = Bitmap.createBitmap(
          kioskWebView.getWidth(),
          kioskWebView.getHeight(),
          Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(bitmap);
        kioskWebView.draw(canvas);

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream);
        String base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP);
        bitmap.recycle();

        JSObject result = new JSObject();
        result.put("base64", base64);
        call.resolve(result);
      } catch (Exception e) {
        call.reject("screenshot capture failed: " + e.getMessage());
      }
    });
  }

  private class TrustAllClient extends WebViewClient {
    @Override
    public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
      // Same internal-network-only trade-off as MainActivity's WebViewClient.
      handler.proceed();
    }

    @Override
    public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
      super.onPageStarted(view, url, favicon);
      JSObject data = new JSObject();
      data.put("url", url);
      notifyListeners("pageLoadStart", data);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
      super.onPageFinished(view, url);
      JSObject data = new JSObject();
      data.put("url", url);
      notifyListeners("pageLoadFinished", data);
    }

    @Override
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
      super.onReceivedError(view, errorCode, description, failingUrl);
      JSObject data = new JSObject();
      data.put("url", failingUrl);
      data.put("description", description);
      notifyListeners("pageLoadError", data);
    }
  }
}
