/**
 * Values compiled into the APK. Keep enrollmentCode empty when the server's
 * ENROLLMENT_CODE is disabled. If an enrollment code is used, rotate or clear
 * it on the server after the devices have registered because APK contents are
 * not a secure secret store.
 */
export const environment = {
  kioskServerUrl: 'https://testmobile.ijn.com.my',
  enrollmentCode: '',
  appVersion: '1.2.0',
} as const;
