import { Component, ElementRef, HostListener, OnInit, QueryList, ViewChildren } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { IonicModule } from '@ionic/angular';
import { AppStateService } from '../../services/app-state.service';
import { StorageService } from '../../services/storage.service';
import { DeviceInfoService, BasicDeviceInfo } from '../../services/device-info.service';
import { WifiSettings } from '../../plugins/wifi-settings.plugin';
import { DeviceName } from '../../plugins/device-name.plugin';
import { DisplayMode, DisplayModeInfo } from '../../plugins/display-mode.plugin';
import { KioskWebView } from '../../plugins/kiosk-webview.plugin';

@Component({
  selector: 'app-settings',
  standalone: true,
  imports: [CommonModule, FormsModule, IonicModule],
  templateUrl: './settings.page.html',
  styleUrl: './settings.page.scss',
})
export class SettingsPage implements OnInit {
  // Every focusable row (Wi-Fi button, save, clear, close) gets a template
  // reference #focusRow so arrow-key navigation below can walk through them
  // in document order — this is the WebView equivalent of RN's D-pad focus
  // handling, since a plain webpage only moves focus on Tab by default, not
  // on ArrowUp/ArrowDown, which is what a TV remote's D-pad actually sends.
  @ViewChildren('focusRow') focusRows!: QueryList<ElementRef<HTMLElement>>;

  urlInput = '';
  savedMessage: string | null = null;
  deviceInfo: BasicDeviceInfo;
  stableId: string | null = null;
  displayModes: DisplayModeInfo[] = [];
  pinnedModeId: number | null = null;
  rotationDegrees = 0;

  constructor(
    public appState: AppStateService,
    private storage: StorageService,
    deviceInfoService: DeviceInfoService
  ) {
    this.deviceInfo = deviceInfoService.getBasicInfo();
  }

  ngOnInit(): void {
    this.urlInput = this.appState.homepage();
    // Give the first row focus immediately so a remote's D-pad works the
    // instant this screen opens, without requiring a Tab press first.
    setTimeout(() => this.focusRows?.first?.nativeElement.focus(), 0);

    DeviceName.getStableId()
      .then(({ id }) => (this.stableId = id))
      .catch(() => (this.stableId = null)); // plain browser, no native plugin

    DisplayMode.listModes()
      .then(({ modes }) => (this.displayModes = modes))
      .catch(() => (this.displayModes = [])); // plain browser, no native plugin

    this.storage.getDisplayModeId().then((id) => (this.pinnedModeId = id));
    this.storage.getRotationDegrees().then((degrees) => (this.rotationDegrees = degrees));
  }

  @HostListener('window:keydown', ['$event'])
  onKeydown(event: KeyboardEvent): void {
    const rows = this.focusRows?.toArray().map((r) => r.nativeElement) ?? [];
    if (rows.length === 0) return;

    const activeIndex = rows.indexOf(document.activeElement as HTMLElement);

    if (event.key === 'ArrowDown') {
      event.preventDefault();
      const next = rows[Math.min(activeIndex + 1, rows.length - 1)] ?? rows[0];
      next.focus();
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      const prev = rows[Math.max(activeIndex - 1, 0)] ?? rows[0];
      prev.focus();
    }
    // Enter/Space/click already trigger native button activation — no
    // special handling needed for "OK" on the remote.
  }

  async openWifiSettings(): Promise<void> {
    try {
      await WifiSettings.openSettings();
    } catch (err) {
      console.log('[kiosk] openWifiSettings failed:', err);
    }
  }

  async setResolution(modeId: number): Promise<void> {
    try {
      await DisplayMode.setMode({ modeId });
      await this.storage.setDisplayModeId(modeId);
      this.pinnedModeId = modeId;
      this.flashMessage('Resolution set — this choice is remembered across restarts.');
    } catch (err) {
      console.log('[kiosk] setResolution failed:', err);
      this.flashMessage('Could not change resolution on this device.');
    }
  }

  async useHighestResolution(): Promise<void> {
    try {
      const result = await DisplayMode.setHighestResolution();
      await this.storage.setDisplayModeId(null);
      this.pinnedModeId = null;
      this.flashMessage(`Using highest available: ${result.width}×${result.height}@${result.refreshRate}Hz`);
    } catch (err) {
      console.log('[kiosk] useHighestResolution failed:', err);
      this.flashMessage('Could not change resolution on this device.');
    }
  }

  async saveUrlOverride(): Promise<void> {
    const trimmed = this.urlInput.trim();
    if (!trimmed) return;
    await this.appState.setUrlOverride(trimmed);
    this.flashMessage('Saved — applied immediately.');
  }

  async clearUrlOverride(): Promise<void> {
    await this.appState.setUrlOverride(null);
    this.urlInput = this.appState.homepage();
    this.flashMessage('Override cleared — back to server-assigned URL.');
  }

  async zoomIn(): Promise<void> {
    await this.applyZoom(Math.min(this.appState.zoomLevel() + 10, 300));
  }

  async zoomOut(): Promise<void> {
    await this.applyZoom(Math.max(this.appState.zoomLevel() - 10, 30));
  }

  async resetZoom(): Promise<void> {
    try {
      await this.appState.setZoomOverride(null); // clears the pin — reverts to the default;
      // note: like clearUrlOverride(), this doesn't re-fetch the server's
      // actual current zoomLevel, it reverts to the hardcoded default. The
      // real server value re-applies next time this device re-registers or
      // receives a fresh update_config push.
      await KioskWebView.setZoom({ percent: this.appState.zoomLevel() });
      this.flashMessage(`Zoom override cleared — reset to ${this.appState.zoomLevel()}%.`);
    } catch (err) {
      console.log('[kiosk] resetZoom failed:', err);
      this.flashMessage('Could not change zoom on this device.');
    }
  }

  private async applyZoom(percent: number): Promise<void> {
    try {
      await this.appState.setZoomOverride(percent);
      await KioskWebView.setZoom({ percent });
      this.flashMessage(`Zoom set to ${percent}% — remembered across restarts.`);
    } catch (err) {
      console.log('[kiosk] applyZoom failed:', err);
      this.flashMessage('Could not change zoom on this device.');
    }
  }

  async setRotation(degrees: number): Promise<void> {
    try {
      await KioskWebView.setRotation({ degrees });
      await this.storage.setRotationDegrees(degrees);
      this.rotationDegrees = degrees;
      this.flashMessage(`Rotation set to ${degrees}° — remembered across restarts.`);
    } catch (err) {
      console.log('[kiosk] setRotation failed:', err);
      this.flashMessage('Could not rotate the display on this device.');
    }
  }

  close(): void {
    this.appState.closeSettings();
  }

  private flashMessage(msg: string): void {
    this.savedMessage = msg;
    setTimeout(() => (this.savedMessage = null), 2500);
  }
}