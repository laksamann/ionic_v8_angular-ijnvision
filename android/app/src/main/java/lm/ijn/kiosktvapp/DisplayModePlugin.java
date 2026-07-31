package com.kiosktvapp;

import android.app.Activity;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Queries and sets the display mode (resolution + refresh rate) the
 * connected TV is driven at over HDMI. This is Display.getSupportedModes()
 * + WindowManager.LayoutParams.preferredDisplayModeId — a normal,
 * permission-free Android API (same one video player apps use to request
 * a 4K/24Hz mode to match video content), NOT a system-level display
 * settings change requiring Device Owner or root. It only takes effect
 * while this app's window is in the foreground, which is exactly right for
 * a kiosk that's supposed to always be in the foreground anyway.
 *
 * "Supported modes" are whatever the physical TV connected over HDMI
 * reports via EDID — this can't force a resolution the display itself
 * doesn't support.
 */
@CapacitorPlugin(name = "DisplayMode")
public class DisplayModePlugin extends Plugin {

  @PluginMethod
  public void listModes(PluginCall call) {
    Activity activity = getActivity();
    Display display = activity.getWindowManager().getDefaultDisplay();
    Display.Mode[] modes = display.getSupportedModes();
    Display.Mode current = display.getMode();

    JSArray modesArray = new JSArray();
    for (Display.Mode mode : modes) {
      JSObject m = new JSObject();
      m.put("modeId", mode.getModeId());
      m.put("width", mode.getPhysicalWidth());
      m.put("height", mode.getPhysicalHeight());
      m.put("refreshRate", mode.getRefreshRate());
      m.put("isCurrent", mode.getModeId() == current.getModeId());
      modesArray.put(m);
    }

    JSObject result = new JSObject();
    result.put("modes", modesArray);
    call.resolve(result);
  }

  @PluginMethod
  public void setMode(PluginCall call) {
    Integer modeId = call.getInt("modeId");
    if (modeId == null) {
      call.reject("modeId is required");
      return;
    }

    getActivity().runOnUiThread(() -> {
      applyModeId(modeId);
      call.resolve();
    });
  }

  /** Convenience: finds and applies the highest-resolution mode the
   * connected display supports (ties broken by highest refresh rate). */
  @PluginMethod
  public void setHighestResolution(PluginCall call) {
    Activity activity = getActivity();
    Display display = activity.getWindowManager().getDefaultDisplay();
    Display.Mode[] modes = display.getSupportedModes();

    Display.Mode best = modes[0];
    for (Display.Mode mode : modes) {
      long bestPixels = (long) best.getPhysicalWidth() * best.getPhysicalHeight();
      long modePixels = (long) mode.getPhysicalWidth() * mode.getPhysicalHeight();
      if (modePixels > bestPixels || (modePixels == bestPixels && mode.getRefreshRate() > best.getRefreshRate())) {
        best = mode;
      }
    }

    final Display.Mode chosen = best;
    getActivity().runOnUiThread(() -> {
      applyModeId(chosen.getModeId());

      JSObject result = new JSObject();
      result.put("modeId", chosen.getModeId());
      result.put("width", chosen.getPhysicalWidth());
      result.put("height", chosen.getPhysicalHeight());
      result.put("refreshRate", chosen.getRefreshRate());
      call.resolve(result);
    });
  }

  private void applyModeId(int modeId) {
    Window window = getActivity().getWindow();
    WindowManager.LayoutParams params = window.getAttributes();
    params.preferredDisplayModeId = modeId;
    window.setAttributes(params);
  }
}
