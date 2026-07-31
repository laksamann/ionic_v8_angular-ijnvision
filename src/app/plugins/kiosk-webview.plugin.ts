import { registerPlugin } from '@capacitor/core';
import type { PluginListenerHandle } from '@capacitor/core';

export interface KioskWebViewPlugin {
  show(options: { url?: string }): Promise<void>;
  hide(): Promise<void>;
  reload(): Promise<void>;
  clearCacheAndReload(): Promise<void>;
  captureScreenshot(): Promise<{ base64: string }>;
  addListener(
    eventName: 'pageLoadStart' | 'pageLoadFinished',
    listenerFunc: (data: { url: string }) => void
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'pageLoadError',
    listenerFunc: (data: { url: string; description: string }) => void
  ): Promise<PluginListenerHandle>;
}

/**
 * Matches android/app/src/main/java/com/kiosktvapp/KioskWebViewPlugin.java —
 * both the plugin `name` here and the @CapacitorPlugin(name = "KioskWebView")
 * annotation on the native side must match exactly.
 *
 * This drives a SEPARATE native WebView layered on top of the Capacitor app,
 * not an <iframe> — see the comment at the top of the .java file for why.
 */
export const KioskWebView = registerPlugin<KioskWebViewPlugin>('KioskWebView');
