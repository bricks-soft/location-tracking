// STUB — owned by Unit 7 (e2e-kit). Signatures are the contract of docs/e2e/architecture.md §8.
import type { LatLon } from './types.ts';
import { notImplemented } from './util.ts';

export interface AdbOptions {
  /** adb serial (`-s`); undefined = the only device */
  serial?: string;
  /** adb binary; default from E2E_ADB / ANDROID_HOME (see readEnv) */
  adbPath?: string;
  /** default timeout of one adb invocation, ms (default 60000) */
  timeoutMs?: number;
}

export interface ExecOptions {
  timeoutMs?: number;
  /** resolve instead of rejecting on a non-zero exit code */
  allowFailure?: boolean;
  /** written to stdin */
  input?: string;
}

export interface ExecResult {
  stdout: string;
  stderr: string;
  code: number;
}

export type IntentExtra = string | number | boolean;

export interface BroadcastOptions {
  /** explicit component `pkg/.Class` or `pkg/full.Class` */
  component?: string;
  /** string extras use `--es`, integers `--ei`, other numbers `--ef`, booleans `--ez` */
  extras?: Record<string, IntentExtra>;
  /** `--receiver-foreground` */
  foreground?: boolean;
  /** `--include-stopped-packages` (deliver to a force-stopped app); default true */
  includeStopped?: boolean;
}

export interface RouteOptions {
  /** ground speed while replaying, m/s (default 10) */
  speedMps?: number;
  /** interval between geo fixes, ms (default 1000) */
  intervalMs?: number;
  signal?: AbortSignal;
}

export interface LogcatDumpOptions {
  /** default ['main', 'system', 'crash'] */
  buffers?: string[];
  /** only lines at or after this time (`logcat -T`) */
  since?: Date;
  /** logcat filter specs, e.g. ['LT-E2E:I', '*:S'] */
  filters?: string[];
}

export type DeviceIdleState = 'ACTIVE' | 'INACTIVE' | 'IDLE_PENDING' | 'SENSING' | 'LOCATING' | 'IDLE' | 'IDLE_MAINTENANCE';

/** `dumpsys deviceidle` wrappers. */
export class DeviceIdle {
  readonly adb: Adb;

  constructor(adb: Adb) {
    this.adb = adb;
  }

  /** `dumpsys deviceidle force-idle` (deep): the device enters IDLE now (screen must be off / battery unplugged). */
  async forceIdle(): Promise<void> {
    return notImplemented('DeviceIdle.forceIdle');
  }

  /** `dumpsys deviceidle unforce` + `dumpsys battery reset` is NOT implied; see Adb.batteryReset. */
  async unforce(): Promise<void> {
    return notImplemented('DeviceIdle.unforce');
  }

  /** `dumpsys deviceidle step [deep|light]`; resolves with the new state. */
  async step(mode: 'deep' | 'light' = 'deep'): Promise<DeviceIdleState> {
    return notImplemented('DeviceIdle.step');
  }

  /** `dumpsys deviceidle get deep`. */
  async state(): Promise<DeviceIdleState> {
    return notImplemented('DeviceIdle.state');
  }

  /** `dumpsys deviceidle whitelist +pkg`: the battery-optimization exemption. */
  async whitelistAdd(appId: string): Promise<void> {
    return notImplemented('DeviceIdle.whitelistAdd');
  }

  /** `dumpsys deviceidle whitelist -pkg`. */
  async whitelistRemove(appId: string): Promise<void> {
    return notImplemented('DeviceIdle.whitelistRemove');
  }

  /** `cmd deviceidle tempwhitelist -d <ms> pkg`: temporary allowlist (allows a background FGS start on 12+). */
  async tempWhitelist(appId: string, durationMs: number): Promise<void> {
    return notImplemented('DeviceIdle.tempWhitelist');
  }
}

/** `adb logcat` wrappers. */
export class Logcat {
  readonly adb: Adb;

  constructor(adb: Adb) {
    this.adb = adb;
  }

  /** `logcat -c` on all buffers. */
  async clear(): Promise<void> {
    return notImplemented('Logcat.clear');
  }

  /** `logcat -d -v threadtime,UTC,year` of the given buffers. */
  async dump(options?: LogcatDumpOptions): Promise<string> {
    return notImplemented('Logcat.dump');
  }

  /** `logcat -d -b crash`. */
  async crashBuffer(): Promise<string> {
    return notImplemented('Logcat.crashBuffer');
  }
}

/**
 * Thin, promise-based adb wrapper for one device. Every method rejects with an Error that includes the command,
 * exit code and stderr. Methods marked (root) need `adb root` (userdebug emulator images; see Adb.root).
 */
export class Adb {
  readonly serial: string | undefined;
  readonly adbPath: string;
  readonly deviceIdle: DeviceIdle;
  readonly logcat: Logcat;

  constructor(options: AdbOptions = {}) {
    this.serial = options.serial;
    this.adbPath = options.adbPath ?? 'adb';
    this.deviceIdle = new DeviceIdle(this);
    this.logcat = new Logcat(this);
  }

  // ---- raw

  /** `adb [-s serial] ...args`. */
  async exec(args: readonly string[], options?: ExecOptions): Promise<ExecResult> {
    return notImplemented('Adb.exec');
  }

  /** `adb shell <command>`; resolves with stdout (trailing newline trimmed). */
  async shell(command: string, options?: ExecOptions): Promise<string> {
    return notImplemented('Adb.shell');
  }

  /** `adb emu <command>` (emulator console). */
  async emu(command: string): Promise<string> {
    return notImplemented('Adb.emu');
  }

  /** `adb root` and wait for the device; resolves false when the image does not allow root (user builds). */
  async root(): Promise<boolean> {
    return notImplemented('Adb.root');
  }

  async isRoot(): Promise<boolean> {
    return notImplemented('Adb.isRoot');
  }

  async getprop(name: string): Promise<string> {
    return notImplemented('Adb.getprop');
  }

  /** `ro.build.version.sdk`. */
  async apiLevel(): Promise<number> {
    return notImplemented('Adb.apiLevel');
  }

  /** `adb shell dumpsys <service> ...args`. */
  async dumpsys(service: string, args?: readonly string[]): Promise<string> {
    return notImplemented('Adb.dumpsys');
  }

  // ---- device lifecycle

  /** `wait-for-device`, then `sys.boot_completed=1` and a responsive package manager. */
  async waitForBoot(timeoutMs?: number): Promise<void> {
    return notImplemented('Adb.waitForBoot');
  }

  /** `adb reboot`, then waitForBoot (and re-root if the device was rooted before). */
  async reboot(options?: { timeoutMs?: number }): Promise<void> {
    return notImplemented('Adb.reboot');
  }

  // ---- packages and processes

  /** `adb install [-r] [-g] <apk>`. */
  async install(apkPath: string, options?: { replace?: boolean; grant?: boolean }): Promise<void> {
    return notImplemented('Adb.install');
  }

  async uninstall(appId: string): Promise<void> {
    return notImplemented('Adb.uninstall');
  }

  async isInstalled(appId: string): Promise<boolean> {
    return notImplemented('Adb.isInstalled');
  }

  /** `pm clear` (also revokes runtime permissions). */
  async clearData(appId: string): Promise<void> {
    return notImplemented('Adb.clearData');
  }

  /** `am start -W` of [activity] (default: the launcher activity); resolves when the activity is displayed. */
  async startApp(appId: string, activity?: string): Promise<void> {
    return notImplemented('Adb.startApp');
  }

  /** `am force-stop`: kills the app and cancels its alarms and jobs (the user's "Force stop"). */
  async forceStop(appId: string): Promise<void> {
    return notImplemented('Adb.forceStop');
  }

  /** `am stop-app` (API 34+): stops the app without cancelling alarms or jobs; falls back to killHard below 34. */
  async stopApp(appId: string): Promise<void> {
    return notImplemented('Adb.stopApp');
  }

  /** `am kill`: kills the process only if it is in the background and safe to kill (what low memory does). */
  async amKill(appId: string): Promise<void> {
    return notImplemented('Adb.amKill');
  }

  /** (root) `kill -9 <pid>` of every process of [appId]: an OEM task killer or LMK kill; alarms and START_STICKY survive. */
  async killHard(appId: string): Promise<void> {
    return notImplemented('Adb.killHard');
  }

  /** `pidof <appId>` (main process); null when not running. */
  async pidof(appId: string): Promise<number | null> {
    return notImplemented('Adb.pidof');
  }

  /** `am broadcast -a <action>` with [BroadcastOptions]; resolves with am's output. */
  async broadcast(action: string, options?: BroadcastOptions): Promise<string> {
    return notImplemented('Adb.broadcast');
  }

  /** `pm grant <appId> <permission>` (full name, e.g. android.permission.ACCESS_FINE_LOCATION). */
  async grant(appId: string, permission: string): Promise<void> {
    return notImplemented('Adb.grant');
  }

  /** `pm revoke`: Android kills the app process when a granted location permission is revoked. */
  async revoke(appId: string, permission: string): Promise<void> {
    return notImplemented('Adb.revoke');
  }

  /** `appops set <appId> <op> <mode>`, e.g. ('RUN_ANY_IN_BACKGROUND', 'ignore'). */
  async setAppOp(appId: string, op: string, mode: 'allow' | 'ignore' | 'deny' | 'default' | 'foreground'): Promise<void> {
    return notImplemented('Adb.setAppOp');
  }

  /** `adb forward tcp:0 <remote>`; resolves with the local port. */
  async forward(remote: string): Promise<number> {
    return notImplemented('Adb.forward');
  }

  async removeForward(localPort: number): Promise<void> {
    return notImplemented('Adb.removeForward');
  }

  /** `run-as <appId> cat <path>` (debuggable apps; path relative to the app's data dir). */
  async runAsCat(appId: string, path: string): Promise<string> {
    return notImplemented('Adb.runAsCat');
  }

  async pull(remote: string, local: string): Promise<void> {
    return notImplemented('Adb.pull');
  }

  /** `adb bugreport <zip>`. */
  async bugreport(localZip: string): Promise<void> {
    return notImplemented('Adb.bugreport');
  }

  async screenshot(localPng: string): Promise<void> {
    return notImplemented('Adb.screenshot');
  }

  // ---- location

  /** Location services on/off (`cmd location set-location-enabled`, falling back to `settings put secure location_mode`). */
  async setLocationEnabled(enabled: boolean): Promise<void> {
    return notImplemented('Adb.setLocationEnabled');
  }

  async isLocationEnabled(): Promise<boolean> {
    return notImplemented('Adb.isLocationEnabled');
  }

  /** Emulator GPS fix: `adb emu geo fix <lon> <lat> [alt]` (note: the console takes longitude first). */
  async geoFix(lat: number, lon: number, options?: { altitude?: number; satellites?: number }): Promise<void> {
    return notImplemented('Adb.geoFix');
  }

  /** Replays [points] as geo fixes, interpolated at [RouteOptions.speedMps] every [RouteOptions.intervalMs]. */
  async playRoute(points: readonly LatLon[], options?: RouteOptions): Promise<void> {
    return notImplemented('Adb.playRoute');
  }

  /** `cmd location providers add-test-provider <provider>` (+ enable): subsequent fixes are mock locations. */
  async addTestProvider(provider?: string): Promise<void> {
    return notImplemented('Adb.addTestProvider');
  }

  /** `cmd location providers set-test-provider-location <provider> --location <lat>,<lon> [--accuracy <m>]`. */
  async setTestLocation(provider: string, lat: number, lon: number, accuracy?: number): Promise<void> {
    return notImplemented('Adb.setTestLocation');
  }

  async removeTestProvider(provider?: string): Promise<void> {
    return notImplemented('Adb.removeTestProvider');
  }

  // ---- power

  /** `dumpsys battery unplug` (required before forcing Doze). */
  async batteryUnplug(): Promise<void> {
    return notImplemented('Adb.batteryUnplug');
  }

  /** `dumpsys battery reset`. */
  async batteryReset(): Promise<void> {
    return notImplemented('Adb.batteryReset');
  }

  /** `dumpsys battery set level <0..100>`. */
  async batterySetLevel(level: number): Promise<void> {
    return notImplemented('Adb.batterySetLevel');
  }

  // ---- connectivity

  /** `cmd connectivity airplane-mode enable|disable`. */
  async setAirplaneMode(on: boolean): Promise<void> {
    return notImplemented('Adb.setAirplaneMode');
  }

  /** `svc wifi enable|disable`. */
  async setWifi(on: boolean): Promise<void> {
    return notImplemented('Adb.setWifi');
  }

  /** `svc data enable|disable`. */
  async setData(on: boolean): Promise<void> {
    return notImplemented('Adb.setData');
  }

  // ---- time

  /** `settings put global auto_time 0|1` (and auto_time_zone). */
  async setAutoTime(on: boolean): Promise<void> {
    return notImplemented('Adb.setAutoTime');
  }

  /** (root) sets the wall clock to [epochMs] (elapsedRealtime is unaffected); call setAutoTime(false) first. */
  async setTime(epochMs: number): Promise<void> {
    return notImplemented('Adb.setTime');
  }

  /** Device time zone, e.g. 'Asia/Riyadh' (`cmd alarm set-timezone` / `service call alarm`). */
  async setTimezone(tz: string): Promise<void> {
    return notImplemented('Adb.setTimezone');
  }

  /** Device wall clock, epoch ms (`date +%s%3N`). */
  async deviceTime(): Promise<number> {
    return notImplemented('Adb.deviceTime');
  }

  // ---- UI

  /** `settings put system font_scale <scale>`: a configuration change that recreates the activity. */
  async setFontScale(scale: number): Promise<void> {
    return notImplemented('Adb.setFontScale');
  }

  /** KEYCODE_HOME: the app goes to the background (activity stopped, not destroyed). */
  async keyHome(): Promise<void> {
    return notImplemented('Adb.keyHome');
  }

  /** KEYCODE_SLEEP. */
  async screenOff(): Promise<void> {
    return notImplemented('Adb.screenOff');
  }

  /** KEYCODE_WAKEUP + `wm dismiss-keyguard`. */
  async screenOn(): Promise<void> {
    return notImplemented('Adb.screenOn');
  }
}
