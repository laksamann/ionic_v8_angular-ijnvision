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
 *
 * SIZE / ZOOM / ROTATION: the WebView's rendered content always matches
 * the device's real screen dimensions exactly (not a fixed reference
 * resolution — an earlier version used a fixed 1920x1080 "virtual canvas"
 * to solve a different problem, but that's not wanted here). Zoom and
 * rotation are both plain Android View transforms (setScaleX/Y,
 * setRotation) — not WebView-specific or web-content-specific mechanisms,
 * which is why they're reliable regardless of the displayed page's own
 * CSS/JS, Android version, or touchscreen presence (see the git history /
 * conversation for three earlier zoom attempts that each broke down
 * differently by depending on WebView/Chromium internals instead).
 *
 * Rotation is a MANUAL, remotely-controlled content rotation (0/90/180/
 * 270), independent of the device's physical orientation sensor — the
 * app itself is locked to landscape at the manifest level
 * (android:screenOrientation="landscape"), so this exists for kiosks
 * physically mounted in a rotated bracket needing portrait-style content
 * on landscape-locked hardware, not for responding to live device tilt.
 * 90/270 swap the WebView's own layout width/height (so the rotated
 * content correctly fills the landscape-shaped container instead of
 * overflowing it) with a translation to re-center the result — see the
 * class Javadoc's rotation math, verified in the conversation before
 * writing this.
 */
@CapacitorPlugin(name = "KioskWebView")
public class KioskWebViewPlugin extends Plugin {

  private WebView kioskWebView;
  private float realWidthPx;
  private float realHeightPx;
  private int targetZoomPercent = 100;
  private int targetRotationDegrees = 0; // one of 0, 90, 180, 270

  private WebView ensureWebView() {
    if (kioskWebView == null) {
      kioskWebView = new WebView(getContext());
      kioskWebView.setBackgroundColor(android.graphics.Color.BLACK);
      kioskWebView.getSettings().setJavaScriptEnabled(true);
      kioskWebView.getSettings().setDomStorageEnabled(true);
      kioskWebView.getSettings().setMediaPlaybackRequiresUserGesture(false);
      // The displayed page is a separate internal system (not something we
      // control). Android WebView's default User-Agent contains "Mobile",
      // which some backends (including ones using mobile-detection
      // libraries) key off server-side to decide which layout to send.
      // Presenting as a desktop browser closes that gap.
      kioskWebView.getSettings().setUserAgentString(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
      );
      kioskWebView.setWebViewClient(new TrustAllClient());

      android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
      getActivity().getWindowManager().getDefaultDisplay().getRealMetrics(dm);
      realWidthPx = dm.widthPixels;
      realHeightPx = dm.heightPixels;

      // Actual initial size/position get set by applyTransform() below —
      // this starting size is just a placeholder until that first call.
      ViewGroup.LayoutParams params =
        new ViewGroup.LayoutParams((int) realWidthPx, (int) realHeightPx);
      getActivity().addContentView(kioskWebView, params);
      // Should already be the default for a FrameLayout-based content
      // view, but explicit rather than assumed — this is what makes
      // rotated/zoomed content that extends beyond the real screen size
      // get cropped at the edge instead of overflowing.
      if (kioskWebView.getParent() instanceof ViewGroup) {
        ((ViewGroup) kioskWebView.getParent()).setClipChildren(true);
      }

      applyTransform();
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
   * Sets zoom as a pure visual scale (100 = normal). Applied instantly via
   * View.setScaleX/setScaleY — no page reload, no dependency on the
   * displayed page's own CSS/JS. See the class Javadoc for why this
   * replaced three earlier, more fragile attempts.
   */
  @PluginMethod
  public void setZoom(PluginCall call) {
    Integer percent = call.getInt("percent");
    if (percent == null) {
      call.reject("percent is required");
      return;
    }
    targetZoomPercent = percent;
    getActivity().runOnUiThread(() -> {
      applyTransform();
      call.resolve();
    });
  }

  @PluginMethod
  public void getZoom(PluginCall call) {
    JSObject result = new JSObject();
    result.put("percent", targetZoomPercent);
    call.resolve(result);
  }

  /**
   * Manually rotates the displayed content — 0, 90, 180, or 270 degrees.
   * Independent of the device's physical orientation sensor; see the
   * class Javadoc for why (the app is landscape-locked at the manifest
   * level, so this is for a kiosk physically mounted rotated, not for
   * responding to live device tilt).
   */
  @PluginMethod
  public void setRotation(PluginCall call) {
    Integer degrees = call.getInt("degrees");
    if (degrees == null) {
      call.reject("degrees is required");
      return;
    }
    int normalized = ((degrees % 360) + 360) % 360;
    if (normalized != 0 && normalized != 90 && normalized != 180 && normalized != 270) {
      call.reject("degrees must be one of 0, 90, 180, 270");
      return;
    }
    targetRotationDegrees = normalized;
    getActivity().runOnUiThread(() -> {
      applyTransform();
      call.resolve();
    });
  }

  @PluginMethod
  public void getRotation(PluginCall call) {
    JSObject result = new JSObject();
    result.put("degrees", targetRotationDegrees);
    call.resolve(result);
  }

  /**
   * Applies both rotation and zoom together. At 90/270, the WebView's own
   * layout width/height are swapped (so its unrotated shape is the real
   * screen's height x width) and a translation re-centers the result —
   * without this, a landscape-shaped WebView rotated 90 in place would
   * either overflow the landscape-locked container or leave it
   * off-center, since rotating around a view's own center doesn't move
   * that center point, but the container's real center is a different
   * point when width != height. This math was verified with a coordinate
   * simulation before writing it — see the conversation for the numbers.
   */
  private void applyTransform() {
    if (kioskWebView == null) return;

    boolean swapped = (targetRotationDegrees == 90 || targetRotationDegrees == 270);
    int layoutWidthPx = (int) (swapped ? realHeightPx : realWidthPx);
    int layoutHeightPx = (int) (swapped ? realWidthPx : realHeightPx);

    ViewGroup.LayoutParams params = kioskWebView.getLayoutParams();
    if (params == null) {
      params = new ViewGroup.LayoutParams(layoutWidthPx, layoutHeightPx);
    } else {
      params.width = layoutWidthPx;
      params.height = layoutHeightPx;
    }
    kioskWebView.setLayoutParams(params);

    kioskWebView.setPivotX(layoutWidthPx / 2f);
    kioskWebView.setPivotY(layoutHeightPx / 2f);
    kioskWebView.setRotation(targetRotationDegrees);

    if (swapped) {
      kioskWebView.setTranslationX((realWidthPx - realHeightPx) / 2f);
      kioskWebView.setTranslationY((realHeightPx - realWidthPx) / 2f);
    } else {
      kioskWebView.setTranslationX(0f);
      kioskWebView.setTranslationY(0f);
    }

    float zoomScale = targetZoomPercent / 100f;
    kioskWebView.setScaleX(zoomScale);
    kioskWebView.setScaleY(zoomScale);
  }

  /** Captures the kiosk WebView's currently rendered content as a JPEG,
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
      // No transform reapplication needed here — setScaleX/Y/setRotation
      // are properties of the Android View itself, not the page content,
      // so they aren't reset by navigating to a new URL.
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