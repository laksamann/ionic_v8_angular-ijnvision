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
 * TWO SEPARATE CONCERNS, handled by two independent mechanisms — an
 * earlier version conflated these and broke the first one while fixing
 * the second, which is why this file has both again:
 *
 *  1. Making the DISPLAYED PAGE'S OWN CSS/JS believe it's on a desktop,
 *     not a mobile device — handled by shouldInterceptRequest() below,
 *     which rewrites the page's own <meta name="viewport"> tag (most
 *     pages, including Bootstrap/Metronic-based ones, ship
 *     `width=device-width`, which Android WebView's mobile-oriented
 *     viewport algorithm honors literally, unlike real desktop browsers
 *     which mostly ignore viewport tags entirely) to an explicit real
 *     pixel width. Also sets a desktop User-Agent, since some backends
 *     make this same "mobile vs desktop" decision server-side instead.
 *
 *  2. Fitting the RESULT to the real screen, at whatever zoom/rotation
 *     the admin dashboard or on-device settings choose — handled by
 *     plain Android View transforms (setScaleX/Y, setRotation), NOT
 *     WebView/Chromium-internal zoom mechanisms. Three earlier attempts
 *     at zoom specifically (WebView.zoomBy() — depends on touch pinch-
 *     zoom detection, unreliable on non-touch Android TV hardware;
 *     WebSettings.setInitialScale() — removed from the compile-time SDK
 *     stub at compileSdkVersion 36; baking zoom into the same viewport
 *     rewrite as #1 — made "contained" zoom fight against "correct
 *     desktop width") each broke down differently by depending on
 *     WebView-internal or web-content-specific behavior instead of a
 *     plain View transform.
 */
@CapacitorPlugin(name = "KioskWebView")
public class KioskWebViewPlugin extends Plugin {

  private WebView kioskWebView;
  // Must match the width used in the viewport meta tag rewrite in
  // shouldInterceptRequest() below — the WebView's own canvas size and
  // the width we tell the page it has must stay in sync, or the two
  // fight each other exactly the way "4 screens wide" just did: telling
  // the page it has 1920 CSS px while our own rendering surface was
  // actually narrower (density-adjusted real screen width) meant the
  // wide layout correctly triggered, but had nowhere to fit, so it
  // overflowed instead of being contained.
  private static final int VIRTUAL_WIDTH_DP = 1920;
  private static final int VIRTUAL_HEIGHT_DP = 1080;

  private float realWidthPx;
  private float realHeightPx;
  private float density = 1f;
  // How tall the actual rendered content is, in CSS px — measured after
  // each page load (see measureContentHeight()) and clamped to at most
  // VIRTUAL_HEIGHT_DP. Starts at the full virtual height as a safe
  // default until the first measurement completes. This only ever
  // shrinks the canvas for shorter content (this file's WIDTH handling —
  // the fix for desktop-vs-mobile layout — is untouched by this; only
  // the height varies).
  private float contentHeightDp = VIRTUAL_HEIGHT_DP;
  // Scales the fixed VIRTUAL_WIDTH_DP x (current contentHeightDp) canvas
  // down (or up) to fit the real screen — recomputed in applyTransform()
  // itself since it depends on contentHeightDp, which changes per page.
  private float fitScale = 1f;
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
      // libraries) key off server-side to decide which layout to send —
      // independent of whatever the viewport rewrite below does, since
      // that's a client-side/rendering concern, this is a server-side one.
      // CRITICAL, and was missing: without setUseWideViewPort(true),
      // WebView's documented behavior is that the viewport meta tag is
      // not consulted AT ALL — layout width is always just the WebView's
      // own View size in CSS pixels, full stop. Every rewrite/removal of
      // that tag across several previous rounds has had ZERO possible
      // effect without this, since the tag was never even being read.
      // setLoadWithOverviewMode scales the resulting wide layout to fit
      // the screen on load, the same way a real desktop browser does.
      kioskWebView.getSettings().setUseWideViewPort(true);
      kioskWebView.getSettings().setLoadWithOverviewMode(true);
      kioskWebView.getSettings().setUserAgentString(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
          "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
      );
      kioskWebView.setWebViewClient(new TrustAllClient());

      android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
      getActivity().getWindowManager().getDefaultDisplay().getRealMetrics(dm);
      realWidthPx = dm.widthPixels;
      realHeightPx = dm.heightPixels;
      density = dm.density;
      // fitScale is computed inside applyTransform() itself now, not here
      // — it depends on contentHeightDp, which gets updated per page load
      // (see measureContentHeight()), so it can't be a fixed one-time value.

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
    int layoutWidthPx = (int) ((swapped ? contentHeightDp : VIRTUAL_WIDTH_DP) * density);
    int layoutHeightPx = (int) ((swapped ? VIRTUAL_WIDTH_DP : contentHeightDp) * density);

    // Recomputed here rather than once — depends on contentHeightDp,
    // which changes per page load (see measureContentHeight()). Width
    // side of this is unchanged from before: still always VIRTUAL_WIDTH_DP.
    fitScale = Math.min(
      realWidthPx / (VIRTUAL_WIDTH_DP * density),
      realHeightPx / (contentHeightDp * density)
    );

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

    // Re-centers the (virtual-canvas-sized) view within the real screen.
    // Rotating/scaling around a view's own center doesn't move that
    // center point, so this same formula works regardless of scale
    // factor or rotation angle — verified with a coordinate simulation
    // before writing it (both properties independently confirmed: this
    // formula centers correctly, and it does so the same way whether
    // fitScale is 1.0 or 0.3).
    kioskWebView.setTranslationX((realWidthPx - layoutWidthPx) / 2f);
    kioskWebView.setTranslationY((realHeightPx - layoutHeightPx) / 2f);

    // fitScale shrinks/grows the fixed 1920x1080 virtual canvas to match
    // the real screen; the user's zoom multiplies on top of that same
    // factor, so both are just one combined View-level scale.
    float combinedScale = fitScale * (targetZoomPercent / 100f);
    kioskWebView.setScaleX(combinedScale);
    kioskWebView.setScaleY(combinedScale);
  }

  /**
   * Measures the actual rendered content height and shrinks the virtual
   * canvas's height to match, if the page is shorter than the full
   * VIRTUAL_HEIGHT_DP assumption — this is what was leaving blank space
   * below shorter pages (e.g. a compact stats dashboard with just a
   * handful of cards, nowhere near 1080dp tall). Deliberately only
   * shrinks, never grows beyond VIRTUAL_HEIGHT_DP — taller-than-1080
   * content scrolling internally is a separate concern from the blank-
   * space-below-short-content issue this specifically addresses, and out
   * of scope here. Width is completely untouched by this — still always
   * VIRTUAL_WIDTH_DP, unrelated to this method.
   */
  private void measureContentHeight() {
    if (kioskWebView == null) return;
    kioskWebView.evaluateJavascript("document.documentElement.scrollHeight", (String result) -> {
      try {
        float measuredDp = Float.parseFloat(result);
        // Floor guards against a near-zero reading from a still-loading
        // or otherwise not-yet-measurable page leaving the canvas
        // absurdly short; ceiling keeps this a shrink-only adjustment.
        contentHeightDp = Math.max(400f, Math.min(measuredDp, VIRTUAL_HEIGHT_DP));
        applyTransform();
      } catch (Exception e) {
        // Failed measurement (unexpected result format, etc.) — leave
        // contentHeightDp at whatever it was before rather than risk an
        // exception disrupting page load.
      }
    });
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
      // setScaleX/Y/setRotation are properties of the Android View itself,
      // not the page content, so they aren't reset by navigating to a new
      // URL (the viewport rewrite in shouldInterceptRequest below IS
      // content-level and gets freshly applied on every fetch
      // automatically). But content HEIGHT varies per page, so that part
      // does need remeasuring after every load.
      measureContentHeight();
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

    /**
     * Rewrites the page's own viewport meta tag to an explicit, large
     * numeric width (e.g. Bootstrap/Metronic's default
     * `width=device-width, initial-scale=1` becomes `width=1920,
     * initial-scale=1`) BEFORE WebView ever parses the original HTML —
     * not via a JS fixup afterward. Chromium computes the viewport's
     * layout effect very early during initial parsing; mutating an
     * already-existing meta tag via JS later isn't reliably re-evaluated
     * the same way a live CSS change would be. Rewriting the actual HTML
     * bytes at the network level guarantees it's correct from the very
     * first parse pass.
     *
     * An earlier version of this just deleted the tag, relying on
     * WebView's own "wide viewport" fallback (which requires
     * setUseWideViewPort(true) in ensureWebView() above — genuinely
     * required for ANY of this to matter, and was accidentally left out
     * for several rounds before this) to take over. That fallback turned
     * out to be a fixed, undocumented internal default (historically
     * around 980px) that isn't wide enough to clear every page's own CSS
     * breakpoints (confirmed: a dashboard using Bootstrap's common 992px
     * "lg" breakpoint still rendered its narrow layout even with the tag
     * correctly removed). Writing an explicit large width instead removes
     * that guesswork entirely.
     *
     * Zoom is handled entirely by the View-level setScaleX/Y in
     * applyTransform() above, unrelated to this tag — an earlier version
     * baked a computed scale into this same rewrite, which made
     * "contained" zoom fight against "correct desktop width"; keeping
     * these two concerns fully separate.
     *
     * Only intercepts the main-frame document itself (not images/CSS/JS/
     * API calls/WebSocket) — anything else, or any failure in this
     * process, falls back to null (WebView loads it normally), so this
     * can never break the page, only fail to fix the viewport in the
     * worst case.
     */
    @Override
    public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, android.webkit.WebResourceRequest request) {
      String reqUrl = request.getUrl().toString();
      if (!request.isForMainFrame()) {
        return null;
      }
      android.util.Log.d("KioskViewport", "intercepting main-frame request: " + reqUrl);
      String method = request.getMethod();
      if (method != null && !method.equalsIgnoreCase("GET")) {
        android.util.Log.d("KioskViewport", "skipping non-GET method: " + method);
        return null;
      }

      try {
        java.net.URL url = new java.net.URL(reqUrl);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        if (conn instanceof javax.net.ssl.HttpsURLConnection) {
          javax.net.ssl.HttpsURLConnection httpsConn = (javax.net.ssl.HttpsURLConnection) conn;
          javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("SSL");
          sslContext.init(null, new javax.net.ssl.TrustManager[] {
            new javax.net.ssl.X509TrustManager() {
              public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
              public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
              public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
            }
          }, new java.security.SecureRandom());
          httpsConn.setSSLSocketFactory(sslContext.getSocketFactory());
          httpsConn.setHostnameVerifier((hostname, session) -> true);
        }
        conn.setRequestProperty("User-Agent",
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        // Explicitly request no compression — simpler and safer than
        // trying to correctly detect/decompress every possible encoding
        // a server might use; HttpURLConnection would otherwise sometimes
        // auto-decompress gzip and sometimes not depending on Android
        // version/configuration, which was a plausible silent-failure
        // mode worth ruling out entirely rather than guessing about.
        conn.setRequestProperty("Accept-Encoding", "identity");
        String cookies = android.webkit.CookieManager.getInstance().getCookie(reqUrl);
        if (cookies != null) conn.setRequestProperty("Cookie", cookies);
        conn.setInstanceFollowRedirects(true);
        conn.connect();

        int status = conn.getResponseCode();
        String contentType = conn.getContentType();
        android.util.Log.d("KioskViewport", "fetch status=" + status + " contentType=" + contentType);

        java.util.List<String> setCookies = conn.getHeaderFields().get("Set-Cookie");
        if (setCookies != null) {
          for (String sc : setCookies) {
            android.webkit.CookieManager.getInstance().setCookie(reqUrl, sc);
          }
        }

        boolean isHtml = contentType != null && contentType.toLowerCase().contains("text/html");

        java.io.InputStream in = (status >= 400) ? conn.getErrorStream() : conn.getInputStream();
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) buffer.write(chunk, 0, read);
        in.close();

        byte[] bodyBytes = buffer.toByteArray();
        android.util.Log.d("KioskViewport", "fetched " + bodyBytes.length + " bytes, isHtml=" + isHtml);

        if (isHtml) {
          String html = new String(bodyBytes, "UTF-8");
          boolean hadViewportBefore = html.toLowerCase().contains("name=\"viewport\"") || html.toLowerCase().contains("name='viewport'");
          android.util.Log.d("KioskViewport", "hadViewportBefore=" + hadViewportBefore);

          // Deliberately NOT just deleting the tag and relying on
          // WebView's own "wide viewport" fallback default — that
          // default is a fixed, undocumented internal value (historically
          // around 980px), which isn't wide enough to clear some pages'
          // own CSS breakpoints (e.g. Bootstrap's common 992px "lg"
          // breakpoint) — confirmed by this specific page still rendering
          // its narrow layout even with the tag correctly removed and
          // setUseWideViewPort(true) correctly enabled. Explicitly setting
          // a real, large numeric width instead removes that guesswork —
          // 1920 clears every standard Bootstrap breakpoint (max is
          // xl=1200) with real margin, rather than hoping an internal
          // default happens to be wide enough.
          final int explicitWidthPx = VIRTUAL_WIDTH_DP;
          String desiredContent = "width=" + explicitWidthPx + ", initial-scale=1";

          // Find every <meta ...> tag independently rather than assuming
          // name="viewport" appears before content="..." in a fixed order
          // — some pages write it the other way around (or with single
          // quotes, or extra attributes in between).
          java.util.regex.Pattern metaTagPattern =
            java.util.regex.Pattern.compile("<meta\\s+[^>]*>", java.util.regex.Pattern.CASE_INSENSITIVE);
          java.util.regex.Matcher m = metaTagPattern.matcher(html);
          StringBuffer sb = new StringBuffer();
          boolean foundViewportTag = false;

          while (m.find()) {
            String tag = m.group();
            boolean isViewportTag = tag.toLowerCase().matches(".*name\\s*=\\s*[\"']viewport[\"'].*");
            if (isViewportTag) {
              foundViewportTag = true;
              String newTag = "<meta name=\"viewport\" content=\"" + desiredContent + "\">";
              android.util.Log.d("KioskViewport", "replacing tag: " + tag + " -> " + newTag);
              m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(newTag));
            } else {
              m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(tag));
            }
          }
          m.appendTail(sb);
          html = sb.toString();

          if (!foundViewportTag) {
            android.util.Log.d("KioskViewport", "no viewport tag found, inserting one after <head>");
            html = html.replaceFirst(
              "(?i)(<head[^>]*>)",
              "$1<meta name=\"viewport\" content=\"" + desiredContent + "\">"
            );
          }

          boolean hasDeviceWidthAfter = html.contains("device-width");
          android.util.Log.d("KioskViewport", "rewrite complete, foundViewportTag=" + foundViewportTag + " hasDeviceWidthAfter=" + hasDeviceWidthAfter);

          bodyBytes = html.getBytes("UTF-8");
        }

        String mimeType = isHtml ? "text/html" : (contentType != null ? contentType.split(";")[0].trim() : "application/octet-stream");
        return new android.webkit.WebResourceResponse(
          mimeType, "UTF-8", new java.io.ByteArrayInputStream(bodyBytes)
        );
      } catch (Exception e) {
        // Logged with full stack trace — previously silent, which meant
        // any failure here (network error, cert issue, parsing problem)
        // looked identical to "working as intended" from the outside:
        // either way the original unmodified page just loaded. Check
        // `adb logcat | grep KioskViewport` after testing to see exactly
        // what's happening.
        android.util.Log.e("KioskViewport", "interception failed for " + reqUrl, e);
        return null;
      }
    }
  }
}
