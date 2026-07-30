import { Component, OnInit } from '@angular/core';
import { IonicModule } from '@ionic/angular';
import { AppStateService } from './services/app-state.service';
import { RemoteBackService } from './services/remote-back.service';
import { KioskPage } from './pages/kiosk/kiosk.page';
import { SettingsPage } from './pages/settings/settings.page';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [IonicModule, KioskPage, SettingsPage],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App implements OnInit {
  constructor(
    public appState: AppStateService,
    private remoteBack: RemoteBackService
  ) {}

  async ngOnInit(): Promise<void> {
    this.remoteBack.register();
    await this.appState.initialize();
  }
}
