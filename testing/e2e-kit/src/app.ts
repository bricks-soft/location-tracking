// The app under test (Unit 7). docs/e2e/architecture.md §8 and the §6 "Test-mode files" addendum.
import type { Adb } from './adb.ts';
import { E2eCommands } from './commands.ts';
import { APP_IDS, type E2eEnv } from './env.ts';
import { shellQuote, waitUntil, type WaitOptions } from './util.ts';
import { WebViewDriver } from './webview.ts';

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

/** Directory of the test-mode files inside the app's data dir (docs/e2e/architecture.md §6 addendum). */
export const TEST_FILES_DIR = 'files/e2e';

/** The plugin example's test-mode file that turns on its e2e mode. */
export const EXAMPLE_E2E_FILE = 'example.json';

/** Minimum API level of each runtime permission (runtime permissions exist from API 23). */
const PERMISSION_MIN_API: Record<string, number> = {
  [PERMISSIONS.coarse]: 23,
  [PERMISSIONS.fine]: 23,
  [PERMISSIONS.background]: 29,
  [PERMISSIONS.activity]: 29,
  [PERMISSIONS.notifications]: 33,
};

/** Grant order: a background location grant needs a foreground location grant first. */
const PERMISSION_ORDER: readonly string[] = [
  PERMISSIONS.coarse,
  PERMISSIONS.fine,
  PERMISSIONS.background,
  PERMISSIONS.activity,
  PERMISSIONS.notifications,
];

/** [PERMISSIONS] entries that exist as runtime permissions on [api], in grant order. */
export function permissionsForApi(api: number): string[] {
  return PERMISSION_ORDER.filter((permission) => api >= (PERMISSION_MIN_API[permission] ?? 23));
}

/**
 * Runtime permissions listed in the `runtime permissions:` sections of `dumpsys package <appId>` (every runtime
 * permission the app requests, granted or not), in the order listed, without duplicates.
 */
export function parseRuntimePermissions(dump: string): string[] {
  const out: string[] = [];
  let inSection = false;
  let sectionIndent = 0;
  for (const line of dump.split('\n')) {
    const indent = line.length - line.trimStart().length;
    if (/^\s*runtime permissions:\s*$/.test(line)) {
      inSection = true;
      sectionIndent = indent;
      continue;
    }
    if (!inSection) continue;
    const match = /^\s*([A-Za-z0-9_.]+): granted=(true|false)/.exec(line);
    if (match && indent > sectionIndent) {
      if (!out.includes(match[1]!)) out.push(match[1]!);
    } else if (line.trim() !== '') {
      inSection = false;
    }
  }
  return out;
}

export interface PrepareOptions {
  /** `install -r` E2E_APK first (default false). An app that is not installed is installed from E2E_APK anyway. */
  reinstall?: boolean;
  /** `pm clear` (default true) */
  clearData?: boolean;
  /**
   * runtime permissions to grant afterwards (default 'all'). 'all' = every PERMISSIONS entry the API level has, then
   * (best effort) every other runtime permission the app requests (`dumpsys package`), so no permission dialog blocks
   * the page. A list grants exactly those; entries the API level does not have are skipped. With `clearData: false`,
   * PERMISSIONS entries that are not granted here are revoked, so the result is the same as after `pm clear`.
   */
  permissions?: 'all' | 'none' | readonly string[];
  /** battery-optimization exemption via the deviceidle allowlist (default false: removed) */
  batteryExempt?: boolean;
  /** launch the app afterwards and wait for its WebView (default false) */
  launch?: boolean;
  /**
   * Test-mode files (file name in `files/e2e/` → JSON value), written before the launch. `null` removes a file. For the
   * plugin example app, `example.json` = `{"e2e": true}` is written unless `testFiles['example.json'] === null`.
   */
  testFiles?: Record<string, unknown>;
}

function checkTestFileName(name: string): void {
  if (!/^[A-Za-z0-9._-]+$/.test(name) || name === '.' || name === '..') {
    throw new Error(`test file name must be a plain file name like 'ff-overrides.json', got '${name}'`);
  }
}

/** A ServiceRecord of `dumpsys activity services`: its component and whether it is a foreground service. */
export interface ServiceRecordInfo {
  /** `package/full.ClassName` (a short `package/.Class` is expanded) */
  component: string;
  isForeground: boolean;
}

/** Parses the `* ServiceRecord{... u0 pkg/cls}` blocks of `dumpsys activity services`. */
export function parseServiceRecords(dump: string): ServiceRecordInfo[] {
  const out: ServiceRecordInfo[] = [];
  let current: ServiceRecordInfo | undefined;
  for (const line of dump.split('\n')) {
    const header = /^\s*\*\s*ServiceRecord\{[^}]*\s(\S+\/\S+)\}/.exec(line);
    if (header) {
      const [pkg, cls] = header[1]!.split('/') as [string, string];
      current = { component: `${pkg}/${cls.startsWith('.') ? pkg + cls : cls}`, isForeground: false };
      out.push(current);
      continue;
    }
    if (current && /\bisForeground=true\b/.test(line)) current.isForeground = true;
    if (line.trim() !== '' && !/^\s/.test(line)) current = undefined;
  }
  return out;
}

/**
 * The app under test on one device. [prepare] also restores a neutral device state: location on, airplane off,
 * Wi-Fi and data on, `deviceidle unforce`, `battery reset`, auto time on (and, with root, the device clock set to the
 * host clock when they differ by more than 30 s), font scale 1, test providers removed, screen on.
 */
export class AppUnderTest {
  readonly adb: Adb;
  readonly appId: string;
  readonly env: E2eEnv;
  readonly commands: E2eCommands;
  private driver: WebViewDriver | undefined;

  constructor(adb: Adb, appId: string, env: E2eEnv) {
    this.adb = adb;
    this.appId = appId;
    this.env = env;
    this.commands = new E2eCommands(adb, appId);
  }

  /**
   * Puts the device and the app into a known state, in this order: force-stop (first, so the previous scenario's app
   * does not react to the device changes below, e.g. upload its queue into the freshly reset back office when airplane
   * mode goes off); neutral device state; install from E2E_APK when `reinstall` or the app is missing; `pm clear`
   * (`clearData`, default true); `appops reset`; runtime permissions; battery exemption (removed by default); test-mode
   * files; launch (`launch`).
   */
  async prepare(options: PrepareOptions = {}): Promise<void> {
    await this.closeWebView();
    await this.adb.forceStop(this.appId);
    await this.neutralDevice();

    const installed = await this.adb.isInstalled(this.appId);
    if (options.reinstall || !installed) {
      if (!this.env.apk) {
        throw new Error(
          options.reinstall
            ? 'prepare({reinstall: true}) needs E2E_APK (path of the APK under test)'
            : `${this.appId} is not installed; install it or set E2E_APK`,
        );
      }
      await this.adb.install(this.env.apk, { replace: true });
    }
    const clearData = options.clearData ?? true;
    if (clearData) await this.adb.clearData(this.appId);
    await this.adb.exec(['shell', `appops reset ${shellQuote(this.appId)}`], { allowFailure: true });

    const api = await this.adb.apiLevel();
    const applicable = permissionsForApi(api);
    const wanted =
      options.permissions === undefined || options.permissions === 'all'
        ? applicable
        : options.permissions === 'none'
          ? []
          : PERMISSION_ORDER.filter((p) => options.permissions!.includes(p) && applicable.includes(p)).concat(
              options.permissions.filter((p) => !PERMISSION_ORDER.includes(p)),
            );
    if (!clearData) {
      for (const permission of [...applicable].reverse()) {
        if (!wanted.includes(permission)) await this.adb.revoke(this.appId, permission);
      }
    }
    for (const permission of wanted) await this.adb.grant(this.appId, permission);
    if (options.permissions === undefined || options.permissions === 'all') {
      // Other runtime permissions the app requests (e.g. from other plugins). Some cannot be granted with pm grant
      // (hard-restricted permissions); those are skipped.
      const dump = await this.adb.exec(['shell', `dumpsys package ${shellQuote(this.appId)}`], { allowFailure: true });
      for (const permission of parseRuntimePermissions(dump.stdout)) {
        if (wanted.includes(permission) || PERMISSION_ORDER.includes(permission)) continue;
        await this.adb.grant(this.appId, permission).catch(() => {});
      }
    }

    if (options.batteryExempt) await this.adb.deviceIdle.whitelistAdd(this.appId);
    else await this.adb.deviceIdle.whitelistRemove(this.appId);

    const files: Record<string, unknown> = {};
    if (this.appId === APP_IDS.plugin) files[EXAMPLE_E2E_FILE] = { e2e: true };
    Object.assign(files, options.testFiles ?? {});
    for (const [name, value] of Object.entries(files)) {
      if (value === null) {
        if (!clearData) await this.removeTestFile(name);
      } else if (value !== undefined) {
        await this.writeTestFile(name, value);
      }
    }

    if (options.launch) await this.launch();
  }

  /** The neutral device state of [prepare] (force-stop not included). */
  private async neutralDevice(): Promise<void> {
    const adb = this.adb;
    await adb.screenOn();
    await adb.deviceIdle.unforce();
    await adb.deviceIdle.restoreDeepIdle();
    await adb.batteryReset();
    await adb.setAirplaneMode(false);
    await adb.setWifi(true);
    await adb.setData(true);
    await adb.setLocationEnabled(true);
    await adb.setAutoTime(true);
    if (await adb.isRoot()) {
      const drift = (await adb.deviceTime()) - Date.now();
      if (Math.abs(drift) > 30_000) await adb.setTime(Date.now());
    }
    await adb.setFontScale(1);
    for (const provider of ['gps', 'network', 'fused']) await adb.removeTestProvider(provider);
  }

  /** Writes a test-mode file `files/e2e/<name>` (JSON of [value]); the page reads it on its next load. */
  async writeTestFile(name: string, value: unknown): Promise<void> {
    checkTestFileName(name);
    await this.adb.runAsWrite(this.appId, `${TEST_FILES_DIR}/${name}`, JSON.stringify(value));
  }

  /** Removes the test-mode file `files/e2e/<name>` (no error when it does not exist). */
  async removeTestFile(name: string): Promise<void> {
    checkTestFileName(name);
    await this.adb.runAsRemove(this.appId, `${TEST_FILES_DIR}/${name}`);
  }

  /** Starts the launcher activity and waits for the WebView DevTools socket and the page's `Capacitor` global. */
  async launch(options: { timeoutMs?: number } = {}): Promise<void> {
    await this.adb.startApp(this.appId);
    await this.webView(options);
  }

  /** A driver for the current process's WebView (reconnects when the pid changed or the connection dropped). */
  async webView(options: { timeoutMs?: number } = {}): Promise<WebViewDriver> {
    const pid = await this.pid();
    if (this.driver && !this.driver.closed && pid !== null && this.driver.pid === pid) return this.driver;
    await this.closeWebView();
    this.driver = await WebViewDriver.connect(this.adb, this.appId, { timeoutMs: options.timeoutMs ?? 60_000 });
    return this.driver;
  }

  /** Closes the cached WebView driver (and its adb forward), if any. */
  async closeWebView(): Promise<void> {
    const driver = this.driver;
    this.driver = undefined;
    if (driver) await driver.close().catch(() => {});
  }

  /** Releases what the app object holds (the WebView connection). The scenario wrapper calls it after every scenario. */
  async dispose(): Promise<void> {
    await this.closeWebView();
  }

  async pid(): Promise<number | null> {
    return this.adb.pidof(this.appId);
  }

  /** Waits until the app has a process (e.g. restored by START_STICKY or an alarm); resolves with its pid. Default timeout 60 s. */
  async waitForProcess(options: WaitOptions = {}): Promise<number> {
    return waitUntil(() => this.pid(), { timeoutMs: 60_000, intervalMs: 1000, message: `a process of ${this.appId}`, ...options });
  }

  /** Waits until the app has no process. Default timeout 30 s. */
  async waitForNoProcess(options: WaitOptions = {}): Promise<void> {
    await waitUntil(async () => (await this.pid()) === null, {
      timeoutMs: 30_000,
      intervalMs: 500,
      message: `${this.appId} to have no process`,
      ...options,
    });
  }

  /**
   * True if an activity of this app exists (created and not destroyed), from the `ActivityRecord{... <appId>/...}` lines
   * of `dumpsys activity activities`.
   */
  async hasActivity(): Promise<boolean> {
    const dump = await this.adb.shell('dumpsys activity activities');
    const pattern = new RegExp(`ActivityRecord\\{[^}]*\\s${this.appId.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}/`);
    return dump.split('\n').some((line) => pattern.test(line));
  }

  /** True if [serviceClass] of this app is running as a foreground service (`dumpsys activity services`). */
  async isForegroundServiceRunning(serviceClass: string = SERVICES.tracking): Promise<boolean> {
    const dump = await this.adb.shell(`dumpsys activity services ${shellQuote(this.appId)}`);
    return parseServiceRecords(dump).some((record) => record.component === `${this.appId}/${serviceClass}` && record.isForeground);
  }

  /** `install -r` of E2E_APK (an app update: MY_PACKAGE_REPLACED). */
  async reinstall(): Promise<void> {
    if (!this.env.apk) throw new Error('reinstall() needs E2E_APK (path of the APK under test)');
    await this.adb.install(this.env.apk, { replace: true });
  }
}
