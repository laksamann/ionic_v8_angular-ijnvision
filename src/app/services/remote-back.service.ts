import { Injectable } from '@angular/core';
import { App } from '@capacitor/app';
import { AppStateService } from './app-state.service';

const OPEN_DELAY_MS = 1500;

/**
 * Kiosk mode intentionally swallows a normal back/menu press (so a visitor
 * with the remote can't back out of the site). Pressing back opens the
 * settings screen after a short delay instead — the same pattern most
 * Android TV kiosk launchers (including Fully Kiosk Browser) use.
 *
 * Note: Capacitor's `backButton` event, like RN's BackHandler, fires once
 * per press rather than giving separate key-down/key-up timing, so this is
 * "press back, then wait ~1.5s" rather than a true measured hold duration —
 * same approximation used in the original React Native build.
 */
@Injectable({ providedIn: 'root' })
export class RemoteBackService {
  private registered = false;

  constructor(private appState: AppStateService) {}

  register(): void {
    if (this.registered) return;
    this.registered = true;

    App.addListener('backButton', () => {
      if (this.appState.settingsOpen()) {
        // Let the settings page's own back button close it instead.
        this.appState.closeSettings();
        return;
      }
      setTimeout(() => {
        this.appState.openSettings();
      }, OPEN_DELAY_MS);
    });
  }
}
