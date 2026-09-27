// STUB — owned by Unit 7 (e2e-kit).
import type { Adb } from './adb.ts';
import { E2eCommands } from './commands.ts';
import type { E2eEnv } from './env.ts';
import { notImplemented, type WaitOptions } from './util.ts';
import type { WebViewDriver } from './webview.ts';

/** Runtime permissions of the plugin (full names). */
export const PERMISSIONS = {
  fine: 'android.permission.ACCESS_FINE_LOCATION',
  coarse: 'android.permission.ACCESS_COARSE_LOCATION',
  background: 'android.permission.ACCESS_BACKGROUND_LOCATION',
  activity: 'android.permission.ACTIVITY_RECOGNITION',
  notifications: 'android.permission.POST_NOTIFICATIONS',
} as const;

/** The plugin's foreground service and the fake PremiseMonitor's (class names). */
export const SERVICES = {
  tracking: 'com.brickssoft.locationtracking.service.LocationTrackingService',
  premise: 'com.brickssoft.premisemonitor.PremiseMonitorService',
} as const;

export interface PrepareOptions {
  /** `install -r` E2E_APK first (default false) */
  reinstall?: boolean;
  /** `pm clear` (default true) */
  clearData?: boolean;
  /** runtime permissions to grant afterwards; 'all' = every PERMISSIONS entry the API level has (default 'all') */
  permissions?: 'all' | 'none' | readonly string[];
  /** battery-optimization exemption via the deviceidle allowlist (default false: removed) */
  batteryExempt?: boolean;
  /** launch the app afterwards and wait for its WebView (default false) */
  launch?: boolean;
}

/**
 * The app under test on one device. [prepare] also restores a neutral device state: location on, airplane off,
 * Wi-Fi and data on, `deviceidle unforce`, `battery reset`, auto time on, font scale 1, test providers removed.
 */
export class AppUnderTest {
  readonly adb: Adb;
  readonly appId: string;
  readonly env: E2eEnv;
  readonly commands: E2eCommands;

  constructor(adb: Adb, appId: string, env: E2eEnv) {
    this.adb = adb;
    this.appId = appId;
    this.env = env;
    this.commands = new E2eCommands(adb, appId);
  }

  async prepare(options?: PrepareOptions): Promise<void> {
    return notImplemented('AppUnderTest.prepare');
  }

  /** Starts the launcher activity and waits for the WebView DevTools socket. */
  async launch(): Promise<void> {
    return notImplemented('AppUnderTest.launch');
  }

  /** A driver for the current process's WebView (reconnects when the pid changed). */
  async webView(): Promise<WebViewDriver> {
    return notImplemented('AppUnderTest.webView');
  }

  async pid(): Promise<number | null> {
    return notImplemented('AppUnderTest.pid');
  }

  /** Waits until the app has a process (e.g. restored by START_STICKY or an alarm); resolves with its pid. */
  async waitForProcess(options?: WaitOptions): Promise<number> {
    return notImplemented('AppUnderTest.waitForProcess');
  }

  /** Waits until the app has no process. */
  async waitForNoProcess(options?: WaitOptions): Promise<void> {
    return notImplemented('AppUnderTest.waitForNoProcess');
  }

  /** True if [serviceClass] of this app is running as a foreground service (`dumpsys activity services`). */
  async isForegroundServiceRunning(serviceClass: string = SERVICES.tracking): Promise<boolean> {
    return notImplemented('AppUnderTest.isForegroundServiceRunning');
  }

  /** `install -r` of E2E_APK (an app update: MY_PACKAGE_REPLACED). */
  async reinstall(): Promise<void> {
    return notImplemented('AppUnderTest.reinstall');
  }
}
