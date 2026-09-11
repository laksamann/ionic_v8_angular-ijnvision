package com.kiosktvapp;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@CapacitorPlugin(name = "AppUpdate")
public class AppUpdatePlugin extends Plugin {
  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  @PluginMethod
  public void downloadAndInstall(PluginCall call) {
    String url = call.getString("url");
    String token = call.getString("token");
    String expectedHash = call.getString("sha256");
    if (url == null || token == null || expectedHash == null ||
        !expectedHash.matches("(?i)^[0-9a-f]{64}$")) {
      call.reject("url, token and a valid SHA-256 hash are required");
      return;
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        !getContext().getPackageManager().canRequestPackageInstalls()) {
      Intent permissionIntent = new Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:" + getContext().getPackageName())
      );
      permissionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      getContext().startActivity(permissionIntent);
      call.reject("Allow 'Install unknown apps' for IJN Vision, then deploy the update again");
      return;
    }

    executor.execute(() -> {
      File updateDir = new File(getContext().getCacheDir(), "updates");
      File apk = new File(updateDir, "latest.apk");
      HttpURLConnection connection = null;
      try {
        if (!updateDir.exists() && !updateDir.mkdirs()) throw new Exception("cannot create update cache");
        connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(180_000);
        connection.setInstanceFollowRedirects(true);
        int responseCode = connection.getResponseCode();
        if (responseCode < 200 || responseCode >= 300) {
          throw new Exception("APK download failed (HTTP " + responseCode + ")");
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(apk, false)) {
          byte[] buffer = new byte[32 * 1024];
          int count;
          while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
            digest.update(buffer, 0, count);
          }
        }
        String actualHash = toHex(digest.digest());
        if (!actualHash.equalsIgnoreCase(expectedHash)) {
          apk.delete();
          throw new Exception("downloaded APK failed SHA-256 verification");
        }

        Uri apkUri = FileProvider.getUriForFile(
          getContext(),
          getContext().getPackageName() + ".fileprovider",
          apk
        );
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(apkUri, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        getActivity().runOnUiThread(() -> {
          try {
            getContext().startActivity(install);
            JSObject result = new JSObject();
            result.put("installerOpened", true);
            call.resolve(result);
          } catch (Exception error) {
            call.reject("could not open Android package installer", error);
          }
        });
      } catch (Exception error) {
        call.reject("APK update failed: " + error.getMessage(), error);
      } finally {
        if (connection != null) connection.disconnect();
      }
    });
  }

  private String toHex(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) result.append(String.format(Locale.US, "%02x", value & 0xff));
    return result.toString();
  }

  @Override
  protected void handleOnDestroy() {
    executor.shutdownNow();
  }
}
