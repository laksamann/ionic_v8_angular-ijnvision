import { registerPlugin } from '@capacitor/core';

export interface AppUpdatePlugin {
  downloadAndInstall(options: {
    url: string;
    token: string;
    sha256: string;
  }): Promise<{ installerOpened: boolean }>;
}

export const AppUpdate = registerPlugin<AppUpdatePlugin>('AppUpdate');
