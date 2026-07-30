import { Injectable } from '@angular/core';
import { Capacitor } from '@capacitor/core';

export interface BasicDeviceInfo {
  platform: string;
  screenWidth: number;
  screenHeight: number;
}

@Injectable({ providedIn: 'root' })
export class DeviceInfoService {
  getBasicInfo(): BasicDeviceInfo {
    return {
      platform: Capacitor.getPlatform(),
      screenWidth: Math.round(window.innerWidth),
      screenHeight: Math.round(window.innerHeight),
    };
  }

  /**
   * For richer heartbeat data (real CPU%, RAM, disk, battery) add the
   * `@capacitor/device` plugin — it's a native plugin so it needs a rebuild,
   * which is why it's left out of this scaffold. Swap the stub heartbeat
   * numbers in kiosk.page.ts for its real readings once installed:
   *
   *   import { Device } from '@capacitor/device';
   *   const info = await Device.getInfo();
   *   const battery = await Device.getBatteryInfo();
   */
}
