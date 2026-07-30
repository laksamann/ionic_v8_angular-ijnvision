import { Component, ElementRef, HostListener, OnInit, QueryList, ViewChildren } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { IonicModule } from '@ionic/angular';
import { AppStateService } from '../../services/app-state.service';
import { StorageService } from '../../services/storage.service';
import { DeviceInfoService, BasicDeviceInfo } from '../../services/device-info.service';
import { WifiSettings } from '../../plugins/wifi-settings.plugin';
import { DeviceName } from '../../plugins/device-name.plugin';

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

  close(): void {
    this.appState.closeSettings();
  }

  private flashMessage(msg: string): void {
    this.savedMessage = msg;
    setTimeout(() => (this.savedMessage = null), 2500);
  }
}
