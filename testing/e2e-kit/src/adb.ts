// adb wrapper of the e2e kit (Unit 7). Signatures are the contract of docs/e2e/architecture.md §8.
import { execFile } from 'node:child_process';
import { writeFile } from 'node:fs/promises';
import { haversineMeters } from './assertions.ts';
import { readEnv } from './env.ts';
import type { LatLon } from './types.ts';
import { shellQuote, sleep, waitUntil } from './util.ts';

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
  /**
   * string extras use `--es`, integers `--ei` (`--el` outside the 32-bit range), other numbers `--ef`, booleans
   * `--ez`
   */
  extras?: Record<string, IntentExtra>;
  /** `--receiver-foreground` */
  foreground?: boolean;
  /** `--include-stopped-packages` (deliver to a force-stopped app); default true */
  includeStopped?: boolean;
  /** timeout of the `am broadcast` call, ms (default: the Adb default). `am broadcast` returns when every receiver finished. */
  timeoutMs?: number;
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
  /** only lines at or after this time (`logcat -T <epoch seconds>`) */
  since?: Date;
  /** only lines whose message matches this regular expression (`logcat -e <regex>`, Android 7+) */
  regex?: string;
  /** logcat filter specs, e.g. ['LT-E2E:I', '*:S'] */
  filters?: string[];
  /** only the last N lines (`logcat -t <N>`); ignored together with [since] */
  tailLines?: number;
  /** timeout of the dump, ms (default: the Adb default) */
  timeoutMs?: number;
}

export type DeviceIdleState = 'ACTIVE' | 'INACTIVE' | 'IDLE_PENDING' | 'SENSING' | 'LOCATING' | 'IDLE' | 'IDLE_MAINTENANCE';

/** An adb invocation that failed: spawn error, timeout, or a non-zero exit code (without `allowFailure`). */
export class AdbError extends Error {
  /** arguments after `adb [-s serial]` */
  readonly args: readonly string[];
  /** exit code; -1 when adb could not be started or was killed */
  readonly code: number;
  readonly stdout: string;
  readonly stderr: string;
  readonly timedOut: boolean;

  constructor(message: string, details: { args: readonly string[]; code: number; stdout: string; stderr: string; timedOut: boolean }) {
    super(message);
    this.name = 'AdbError';
    this.args = details.args;
    this.code = details.code;
    this.stdout = details.stdout;
    this.stderr = details.stderr;
    this.timedOut = details.timedOut;
  }
}

/** Largest stdout/stderr accepted from one adb call (logcat dumps and bugreports can be large). */
const MAX_BUFFER = 512 * 1024 * 1024;

/** Output of an Android shell command that reports a failure with exit code 0 (the default check of [Adb.shellChecked]). */
export const COMMAND_FAILED = /exception|unknown command|error:/i;

/** Characters allowed in paths passed to `run-as` helpers (no quoting needed inside `sh -c`). */
const SAFE_REL_PATH = /^[A-Za-z0-9._-]+(\/[A-Za-z0-9._-]+)*$/;

function checkRelPath(relPath: string): void {
  if (!SAFE_REL_PATH.test(relPath) || relPath.split('/').some((part) => part === '..' || part === '.')) {
    throw new Error(`unsupported app-relative path '${relPath}': use [A-Za-z0-9._-] segments separated by '/'`);
  }
}

function tail(text: string, max = 2000): string {
  const trimmed = text.trim();
  return trimmed.length > max ? `...${trimmed.slice(-max)}` : trimmed;
}

/** `dumpsys deviceidle` wrappers. */
export class DeviceIdle {
  readonly adb: Adb;

  constructor(adb: Adb) {
    this.adb = adb;
  }

  /**
   * `dumpsys deviceidle force-idle` (deep): the device enters IDLE now. Emulators ship with deep idle disabled
   * (`config_enableAutoPowerModes` false), where force-idle answers "not enabled"; so when `dumpsys deviceidle enabled
   * deep` prints 0, this first runs `dumpsys deviceidle enable deep` and records that in the device property
   * [DeviceIdle.KIT_ENABLED_PROP], so [restoreDeepIdle] (called by AppUnderTest.prepare) can disable it again, also from
   * another test process.
   */
  async forceIdle(): Promise<void> {
    const enabled = (await this.adb.exec(['shell', 'dumpsys deviceidle enabled deep'], { allowFailure: true })).stdout.trim();
    if (enabled === '0') {
      await this.adb.shellChecked('dumpsys deviceidle enable deep');
      this.enabledByKit = true;
      await this.adb.exec(['shell', `setprop ${DeviceIdle.KIT_ENABLED_PROP} 1`], { allowFailure: true });
    }
    await this.adb.shellChecked('dumpsys deviceidle force-idle', /unable|not enabled|exception|error:/i);
  }

  /** Device property that marks deep idle as enabled by [forceIdle]. */
  static readonly KIT_ENABLED_PROP = 'debug.e2ekit.deep_idle_enabled';
  private enabledByKit = false;

  /** `dumpsys deviceidle unforce` + `dumpsys battery reset` is NOT implied; see Adb.batteryReset. */
  async unforce(): Promise<void> {
    await this.adb.shell('dumpsys deviceidle unforce');
  }

  /** `dumpsys deviceidle disable deep` if [forceIdle] enabled deep idle (this process or the device property says so). */
  async restoreDeepIdle(): Promise<void> {
    const marked = (await this.adb.exec(['shell', `getprop ${DeviceIdle.KIT_ENABLED_PROP}`], { allowFailure: true })).stdout.trim() === '1';
    if (!marked && !this.enabledByKit) return;
    await this.adb.shell('dumpsys deviceidle disable deep');
    this.enabledByKit = false;
    await this.adb.exec(['shell', `setprop ${DeviceIdle.KIT_ENABLED_PROP} 0`], { allowFailure: true });
  }

  /** `dumpsys deviceidle step [deep|light]`; resolves with the new state. */
  async step(mode: 'deep' | 'light' = 'deep'): Promise<DeviceIdleState> {
    const out = await this.adb.shell(`dumpsys deviceidle step ${mode}`);
    const match = /Stepped to (?:deep|light):?\s*(\w+)/i.exec(out);
    if (match) return match[1] as DeviceIdleState;
    return mode === 'deep' ? this.state() : ((await this.adb.shell('dumpsys deviceidle get light')).trim() as DeviceIdleState);
  }

  /** `dumpsys deviceidle get deep`. */
  async state(): Promise<DeviceIdleState> {
    return (await this.adb.shell('dumpsys deviceidle get deep')).trim() as DeviceIdleState;
  }

  /** `dumpsys deviceidle whitelist +pkg`: the battery-optimization exemption. */
  async whitelistAdd(appId: string): Promise<void> {
    await this.adb.shellChecked(`dumpsys deviceidle whitelist +${shellQuote(appId)}`, /unknown package|exception|error:/i);
  }

  /** `dumpsys deviceidle whitelist -pkg` (a package that is not in the list is not an error). */
  async whitelistRemove(appId: string): Promise<void> {
    await this.adb.exec(['shell', `dumpsys deviceidle whitelist -${shellQuote(appId)}`], { allowFailure: true });
  }

  /** `dumpsys deviceidle tempwhitelist -d <ms> pkg`: temporary allowlist (allows a background FGS start on 12+). */
  async tempWhitelist(appId: string, durationMs: number): Promise<void> {
    const ms = Math.max(1, Math.round(durationMs));
    await this.adb.shellChecked(`dumpsys deviceidle tempwhitelist -d ${ms} ${shellQuote(appId)}`, /unknown package|exception|error:/i);
  }
}

/** `adb logcat` wrappers. Output format: `threadtime` with `UTC` and `year` (e.g. `2026-09-27 10:15:30.123  1234  1250 I TAG: msg`). */
export class Logcat {
  readonly adb: Adb;

  constructor(adb: Adb) {
    this.adb = adb;
  }

  /** `logcat -c` on all buffers (`-b all`; falls back to the main, system, crash and events buffers). */
  async clear(): Promise<void> {
    const all = await this.adb.exec(['logcat', '-b', 'all', '-c'], { allowFailure: true });
    if (all.code === 0) return;
    await this.adb.exec(['logcat', '-b', 'main', '-b', 'system', '-b', 'crash', '-b', 'events', '-c']);
  }

  /** `logcat -d -v threadtime -v UTC -v year` of the given buffers. */
  async dump(options: LogcatDumpOptions = {}): Promise<string> {
    const args = ['logcat', '-d', '-v', 'threadtime', '-v', 'UTC', '-v', 'year'];
    for (const buffer of options.buffers ?? ['main', 'system', 'crash']) args.push('-b', buffer);
    if (options.since) args.push('-T', (options.since.getTime() / 1000).toFixed(3));
    else if (options.tailLines !== undefined) args.push('-t', String(Math.max(1, Math.floor(options.tailLines))));
    if (options.regex !== undefined) args.push('-e', options.regex);
    args.push(...(options.filters ?? []));
    const result = await this.adb.exec(args, { timeoutMs: options.timeoutMs });
    return result.stdout;
  }

  /** `logcat -d -b crash` (threadtime, UTC, year). */
  async crashBuffer(): Promise<string> {
    return this.dump({ buffers: ['crash'] });
  }
}

/**
 * Splits [points] into fixes [stepM] meters apart along the polyline (linear interpolation per segment), always
 * including the first and the last point. Used by [Adb.playRoute].
 */
export function interpolateRoute(points: readonly LatLon[], stepM: number): LatLon[] {
  if (!(stepM > 0) || !Number.isFinite(stepM)) throw new Error(`route step must be a positive number of meters, got ${stepM}`);
  if (points.length === 0) return [];
  const first = points[0]!;
  const out: LatLon[] = [{ ...first }];
  // Distance from the last emitted point to the start of the current segment.
  let carried = 0;
  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1]!;
    const b = points[i]!;
    const length = haversineMeters(a, b);
    let pos = stepM - carried;
    while (length > 0 && pos <= length + 1e-9) {
      const f = pos / length;
      const point: LatLon = { lat: a.lat + (b.lat - a.lat) * f, lon: a.lon + (b.lon - a.lon) * f };
      if (a.alt !== undefined && b.alt !== undefined) point.alt = a.alt + (b.alt - a.alt) * f;
      out.push(point);
      pos += stepM;
    }
    carried = length - (pos - stepM);
  }
  const last = points[points.length - 1]!;
  const remainder = haversineMeters(out[out.length - 1]!, last);
  // A remainder below 5% of a step (rounding of the route length) moves the last point onto the end instead of
  // adding a fix a few centimeters later.
  if (remainder > 0.01 && remainder < stepM * 0.05 && out.length > 1) out[out.length - 1] = { ...last };
  else if (remainder > 0.01) out.push({ ...last });
  return out;
}

/**
 * Thin, promise-based adb wrapper for one device. Every method rejects with an [AdbError] that includes the command,
 * exit code and stderr. Methods marked (root) need `adb root` (userdebug emulator images; see Adb.root).
 */
export class Adb {
  readonly serial: string | undefined;
  readonly adbPath: string;
  readonly timeoutMs: number;
  readonly deviceIdle: DeviceIdle;
  readonly logcat: Logcat;
  /** true after a successful [root]; [reboot] then roots again. */
  private rooted = false;
  private apiCache: number | undefined;
  private readonly launcherCache = new Map<string, string>();

  constructor(options: AdbOptions = {}) {
    this.serial = options.serial;
    this.adbPath = options.adbPath ?? readEnv().adbPath;
    this.timeoutMs = options.timeoutMs ?? 60_000;
    this.deviceIdle = new DeviceIdle(this);
    this.logcat = new Logcat(this);
  }

  // ---- raw

  /** `adb [-s serial] ...args`, stdout as bytes (screenshots). */
  async execRaw(args: readonly string[], options: ExecOptions = {}): Promise<{ stdout: Buffer; stderr: string; code: number }> {
    const fullArgs = this.serial ? ['-s', this.serial, ...args] : [...args];
    const timeoutMs = options.timeoutMs ?? this.timeoutMs;
    const printable = `adb ${fullArgs.map(shellQuote).join(' ')}`;
    return new Promise((resolve, reject) => {
      const child = execFile(
        this.adbPath,
        fullArgs,
        { encoding: 'buffer', timeout: timeoutMs, maxBuffer: MAX_BUFFER, windowsHide: true },
        (error, stdoutBuf, stderrBuf) => {
          const stdout = Buffer.isBuffer(stdoutBuf) ? stdoutBuf : Buffer.from(stdoutBuf ?? '');
          const stderr = (Buffer.isBuffer(stderrBuf) ? stderrBuf : Buffer.from(stderrBuf ?? '')).toString('utf8');
          if (!error) {
            resolve({ stdout, stderr, code: 0 });
            return;
          }
          const exitCode = typeof error.code === 'number' ? error.code : -1;
          // A timeout kills adb with a signal (code null); a maxBuffer overflow also kills it but sets a string code.
          const timedOut = error.killed === true && error.code == null;
          if (exitCode >= 0 && !timedOut && options.allowFailure) {
            resolve({ stdout, stderr, code: exitCode });
            return;
          }
          const why = timedOut
            ? `timed out after ${timeoutMs} ms`
            : exitCode >= 0
              ? `failed (exit ${exitCode})`
              : `could not run (${String(error.code ?? error.message)})`;
          const output = tail(stderr) || tail(stdout.toString('utf8'));
          reject(
            new AdbError(`${printable} ${why}${output ? `: ${output}` : ''}`, {
              args: [...args],
              code: exitCode,
              stdout: stdout.toString('utf8'),
              stderr,
              timedOut,
            }),
          );
        },
      );
      // Always close stdin: `adb shell` forwards stdin, and a command that reads it (cat > file) needs EOF.
      child.stdin?.on('error', () => {});
      child.stdin?.end(options.input ?? '');
    });
  }

  /** `adb [-s serial] ...args`. */
  async exec(args: readonly string[], options?: ExecOptions): Promise<ExecResult> {
    const result = await this.execRaw(args, options);
    return { stdout: result.stdout.toString('utf8'), stderr: result.stderr, code: result.code };
  }

  /** `adb shell <command>`; resolves with stdout (CRLF normalized, trailing newlines trimmed). */
  async shell(command: string, options?: ExecOptions): Promise<string> {
    const result = await this.exec(['shell', command], options);
    return result.stdout.replace(/\r\n/g, '\n').replace(/\n+$/, '');
  }

  /** `adb emu <command>` (emulator console); rejects when the console answers `KO`. */
  async emu(command: string): Promise<string> {
    const result = await this.exec(['emu', ...command.trim().split(/\s+/)]);
    const text = `${result.stdout}\n${result.stderr}`;
    if (/^KO\b/m.test(text)) throw new Error(`adb emu ${command} failed: ${tail(text)}`);
    return result.stdout.replace(/\r\n/g, '\n').trim();
  }

  /** `adb root` and wait for the device; resolves false when the image does not allow root (user builds). */
  async root(): Promise<boolean> {
    const result = await this.exec(['root'], { allowFailure: true, timeoutMs: 30_000 });
    const text = `${result.stdout}\n${result.stderr}`;
    if (/cannot run as root|not allowed|production builds/i.test(text)) {
      this.rooted = false;
      return false;
    }
    await this.exec(['wait-for-device'], { timeoutMs: 60_000 });
    try {
      // adbd restarts after `adb root`; the first shell calls can fail while it reconnects.
      await waitUntil(() => this.isRoot(), { timeoutMs: 20_000, intervalMs: 500, message: 'adbd running as root' });
      this.rooted = true;
      return true;
    } catch {
      this.rooted = false;
      return false;
    }
  }

  async isRoot(): Promise<boolean> {
    return (await this.shell('id -u', { timeoutMs: 15_000 })).trim() === '0';
  }

  async getprop(name: string): Promise<string> {
    return (await this.shell(`getprop ${shellQuote(name)}`)).trim();
  }

  /** `ro.build.version.sdk` (cached). */
  async apiLevel(): Promise<number> {
    if (this.apiCache === undefined) {
      const value = Number.parseInt(await this.getprop('ro.build.version.sdk'), 10);
      if (!Number.isFinite(value)) throw new Error('could not read ro.build.version.sdk');
      this.apiCache = value;
    }
    return this.apiCache;
  }

  /** `adb shell dumpsys <service> ...args`. */
  async dumpsys(service: string, args: readonly string[] = []): Promise<string> {
    return this.shell(['dumpsys', service, ...args].map(shellQuote).join(' '));
  }

  // ---- device lifecycle

  /**
   * `wait-for-device`, then `sys.boot_completed=1` (and `dev.bootcomplete=1` where the image sets it) and a responsive
   * package manager (`pm path android`). Default timeout 240 s.
   */
  async waitForBoot(timeoutMs = 240_000): Promise<void> {
    const deadline = Date.now() + timeoutMs;
    await this.exec(['wait-for-device'], { timeoutMs });
    await waitUntil(
      async () => {
        const props = (await this.shell('getprop sys.boot_completed; getprop dev.bootcomplete', { timeoutMs: 15_000 })).split('\n');
        const bootCompleted = (props[0] ?? '').trim();
        const devBootComplete = (props[1] ?? '').trim();
        if (bootCompleted !== '1' || (devBootComplete !== '' && devBootComplete !== '1')) return false;
        const pm = await this.exec(['shell', 'pm path android'], { allowFailure: true, timeoutMs: 15_000 });
        return pm.code === 0 && pm.stdout.includes('package:');
      },
      {
        timeoutMs: Math.max(0, deadline - Date.now()),
        intervalMs: 1000,
        message: 'the device to finish booting (sys.boot_completed=1, package manager answering)',
      },
    );
  }

  /** The kernel boot id (`/proc/sys/kernel/random/boot_id`); changes with every boot. */
  async bootId(): Promise<string> {
    return (await this.shell('cat /proc/sys/kernel/random/boot_id', { timeoutMs: 15_000 })).trim();
  }

  /**
   * `adb reboot`, then waits until the device reports a new boot id and waitForBoot, and runs `adb root` again if the
   * kit had root before. Default timeout 300 s.
   */
  async reboot(options: { timeoutMs?: number } = {}): Promise<void> {
    const timeoutMs = options.timeoutMs ?? 300_000;
    const deadline = Date.now() + timeoutMs;
    const hadRoot = this.rooted || (await this.isRoot().catch(() => false));
    const bootBefore = await this.bootId().catch(() => '');
    // A framework reboot, the power menu's path: PowerManager.reboot runs ShutdownThread (ACTION_SHUTDOWN, then the
    // system services write their pending state). A plain `adb reboot` makes init stop system_server directly: on the
    // CI emulator, runtime permissions granted about 6 s earlier were missing after such a reboot (field-force F-03).
    // `svc power reboot` can end with an error when the connection drops during the shutdown, so its result is only
    // used to decide on the fallback: when svc is missing or refuses and the device still runs the same boot.
    const svc = await this.exec(['shell', 'svc power reboot'], { timeoutMs: 60_000, allowFailure: true });
    if (svc.code !== 0 && /not found|unknown|usage|error/i.test(`${svc.stdout}\n${svc.stderr}`)) {
      const stillSameBoot = bootBefore !== '' && (await this.bootId().catch(() => '')) === bootBefore;
      if (stillSameBoot || bootBefore === '') await this.exec(['reboot'], { timeoutMs: 60_000 });
    }
    if (bootBefore === '') {
      await sleep(10_000);
    } else {
      await this.exec(['wait-for-device'], { timeoutMs: Math.max(1000, deadline - Date.now()) });
      await waitUntil(
        async () => {
          const id = await this.bootId();
          return id !== '' && id !== bootBefore;
        },
        { timeoutMs: Math.max(0, deadline - Date.now()), intervalMs: 1000, message: 'a new boot id after the reboot' },
      );
    }
    await this.waitForBoot(Math.max(1000, deadline - Date.now()));
    if (hadRoot) {
      await this.root();
      // A kernel wake lock does not survive a reboot. Without it an emulator in deep Doze with the screen off can
      // suspend, and adbd drops the connection (first API 29 CI run). `.github/scripts/run-e2e.sh` sets the first one.
      await this.exec(['shell', 'echo e2e-kit > /sys/power/wake_lock'], { allowFailure: true });
    }
  }

  // ---- packages and processes

  /** `adb install [-r] [-g] <apk>`; rejects unless adb prints `Success`. */
  async install(apkPath: string, options: { replace?: boolean; grant?: boolean } = {}): Promise<void> {
    const args = ['install'];
    if (options.replace) args.push('-r');
    if (options.grant) args.push('-g');
    args.push(apkPath);
    const result = await this.exec(args, { timeoutMs: 300_000, allowFailure: true });
    const text = `${result.stdout}\n${result.stderr}`;
    if (result.code !== 0 || !/\bSuccess\b/.test(text)) throw new Error(`adb install ${apkPath} failed: ${tail(text)}`);
  }

  /** `adb uninstall`; resolves when the app is not installed afterwards (also when it was not installed). */
  async uninstall(appId: string): Promise<void> {
    const result = await this.exec(['uninstall', appId], { allowFailure: true, timeoutMs: 120_000 });
    if (/\bSuccess\b/.test(result.stdout)) return;
    if (await this.isInstalled(appId)) throw new Error(`adb uninstall ${appId} failed: ${tail(result.stdout + result.stderr)}`);
  }

  /** The app's Linux uid (`pm list packages -U <appId>`), e.g. 10123 (to find it in `dumpsys` output). */
  async appUid(appId: string): Promise<number> {
    const out = await this.shell(`pm list packages -U ${shellQuote(appId)}`);
    for (const line of out.split('\n')) {
      const match = /^package:(\S+) uid:(\d+)/.exec(line.trim());
      if (match && match[1] === appId) return Number.parseInt(match[2]!, 10);
    }
    throw new Error(`no uid for ${appId} in pm list packages -U (is it installed?)`);
  }

  async isInstalled(appId: string): Promise<boolean> {
    const result = await this.exec(['shell', `pm path ${shellQuote(appId)}`], { allowFailure: true });
    return result.code === 0 && result.stdout.includes('package:');
  }

  /** `pm clear` (also revokes runtime permissions). */
  async clearData(appId: string): Promise<void> {
    const out = await this.shell(`pm clear ${shellQuote(appId)}`);
    if (!/\bSuccess\b/.test(out)) throw new Error(`pm clear ${appId} failed: ${tail(out)}`);
  }

  /** The launcher activity `pkg/.Class` (`cmd package resolve-activity --brief`, cached). */
  async launcherActivity(appId: string): Promise<string> {
    const cached = this.launcherCache.get(appId);
    if (cached) return cached;
    const out = await this.shell(
      `cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER ${shellQuote(appId)}`,
    );
    const component = out
      .split('\n')
      .map((line) => line.trim())
      .filter((line) => line.startsWith(`${appId}/`))
      .pop();
    if (!component) throw new Error(`no launcher activity found for ${appId} (is it installed?): ${tail(out)}`);
    this.launcherCache.set(appId, component);
    return component;
  }

  /** `am start -W` of [activity] (default: the launcher activity); resolves when the activity is displayed. */
  async startApp(appId: string, activity?: string): Promise<void> {
    const component = activity
      ? activity.includes('/')
        ? activity
        : `${appId}/${activity}`
      : await this.launcherActivity(appId);
    const out = await this.shell(
      `am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${shellQuote(component)}`,
      { timeoutMs: 90_000 },
    );
    if (/^Error|Exception|Status: timeout/m.test(out)) throw new Error(`am start ${component} failed: ${tail(out)}`);
  }

  /** `am force-stop`: kills the app and cancels its alarms and jobs (the user's "Force stop"). */
  async forceStop(appId: string): Promise<void> {
    await this.shell(`am force-stop ${shellQuote(appId)}`);
  }

  /** `am stop-app` (API 34+): stops the app without cancelling alarms or jobs; falls back to killHard below 34. */
  async stopApp(appId: string): Promise<void> {
    if ((await this.apiLevel()) >= 34) await this.shell(`am stop-app ${shellQuote(appId)}`);
    else await this.killHard(appId);
  }

  /** `am kill`: kills the process only if it is in the background and safe to kill (what low memory does). */
  async amKill(appId: string): Promise<void> {
    await this.shell(`am kill ${shellQuote(appId)}`);
  }

  /** pids of every process of [appId] (the main process and `appId:*` processes), from `ps -A -o PID,NAME`. */
  async pidsOf(appId: string): Promise<number[]> {
    const out = await this.shell('ps -A -o PID,NAME');
    const pids: number[] = [];
    for (const line of out.split('\n')) {
      const match = /^\s*(\d+)\s+(\S+)\s*$/.exec(line);
      if (!match) continue;
      const name = match[2]!;
      if (name === appId || name.startsWith(`${appId}:`)) pids.push(Number.parseInt(match[1]!, 10));
    }
    return pids;
  }

  /**
   * (root) `kill -9 <pid>` of every process of [appId]: an OEM task killer or LMK kill; alarms and START_STICKY survive.
   * Without root it sends the same signal through `run-as <appId> kill -9` (same uid; debuggable builds only).
   * Resolves with the killed pids (empty when the app had no process).
   */
  async killHard(appId: string): Promise<number[]> {
    const pids = await this.pidsOf(appId);
    if (pids.length === 0) return [];
    const list = pids.join(' ');
    const command = (await this.isRoot()) ? `kill -9 ${list}` : `run-as ${shellQuote(appId)} kill -9 ${list}`;
    // kill exits 1 when one of the pids is already gone (e.g. a :remote process that died with the main process), so
    // the exit code is not the result: the pids still alive afterwards are.
    const result = await this.exec(['shell', command], { allowFailure: true });
    try {
      await waitUntil(async () => !(await this.pidsOf(appId)).some((pid) => pids.includes(pid)), {
        timeoutMs: 5000,
        intervalMs: 200,
        message: `the processes ${list} of ${appId} to exit`,
      });
    } catch (error) {
      throw new Error(`${command} did not kill ${appId}: ${tail(result.stdout + result.stderr) || (error as Error).message}`);
    }
    return pids;
  }

  /** `pidof <appId>` (main process); null when not running. */
  async pidof(appId: string): Promise<number | null> {
    const result = await this.exec(['shell', `pidof ${shellQuote(appId)}`], { allowFailure: true });
    const match = /\d+/.exec(result.stdout);
    return match ? Number.parseInt(match[0], 10) : null;
  }

  /** `am broadcast -a <action>` with [BroadcastOptions]; resolves with am's output. */
  async broadcast(action: string, options: BroadcastOptions = {}): Promise<string> {
    const parts = ['am', 'broadcast', '-a', action];
    if (options.component) parts.push('-n', options.component);
    if (options.includeStopped !== false) parts.push('--include-stopped-packages');
    if (options.foreground) parts.push('--receiver-foreground');
    for (const [key, value] of Object.entries(options.extras ?? {})) {
      if (typeof value === 'string') parts.push('--es', key, value);
      else if (typeof value === 'boolean') parts.push('--ez', key, String(value));
      else if (Number.isInteger(value)) parts.push(Math.abs(value) <= 2_147_483_647 ? '--ei' : '--el', key, String(value));
      else parts.push('--ef', key, String(value));
    }
    const out = await this.shell(parts.map(shellQuote).join(' '), { timeoutMs: options.timeoutMs });
    if (/^Error|Exception/m.test(out)) throw new Error(`am broadcast ${action} failed: ${tail(out)}`);
    return out;
  }

  /** `pm grant <appId> <permission>` (full name, e.g. android.permission.ACCESS_FINE_LOCATION). */
  async grant(appId: string, permission: string): Promise<void> {
    await this.shell(`pm grant ${shellQuote(appId)} ${shellQuote(permission)}`);
  }

  /** `pm revoke`: Android kills the app process when a granted location permission is revoked. */
  async revoke(appId: string, permission: string): Promise<void> {
    await this.shell(`pm revoke ${shellQuote(appId)} ${shellQuote(permission)}`);
  }

  /** `appops set <appId> <op> <mode>`, e.g. ('RUN_ANY_IN_BACKGROUND', 'ignore'). */
  async setAppOp(appId: string, op: string, mode: 'allow' | 'ignore' | 'deny' | 'default' | 'foreground'): Promise<void> {
    await this.shell(`appops set ${shellQuote(appId)} ${shellQuote(op)} ${mode}`);
  }

  /** `adb forward tcp:0 <remote>`; resolves with the local port. */
  async forward(remote: string): Promise<number> {
    const result = await this.exec(['forward', 'tcp:0', remote]);
    const port = Number.parseInt(result.stdout.trim(), 10);
    if (!Number.isInteger(port) || port <= 0) throw new Error(`adb forward tcp:0 ${remote} printed no port: '${tail(result.stdout)}'`);
    return port;
  }

  async removeForward(localPort: number): Promise<void> {
    await this.exec(['forward', '--remove', `tcp:${localPort}`], { allowFailure: true });
  }

  /** `run-as <appId> cat <path>` (debuggable apps; path relative to the app's data dir). Resolves with the exact bytes as UTF-8. */
  async runAsCat(appId: string, path: string): Promise<string> {
    const result = await this.exec(['shell', `run-as ${shellQuote(appId)} cat ${shellQuote(path)}`]);
    return result.stdout;
  }

  /**
   * Writes [text] to [relPath] (relative to the app's data dir, e.g. `files/e2e/example.json`) as the app's user:
   * `run-as <appId> sh -c 'mkdir -p <dir> && cat > <relPath>'` with [text] on stdin, then reads it back and compares.
   */
  async runAsWrite(appId: string, relPath: string, text: string): Promise<void> {
    checkRelPath(relPath);
    const slash = relPath.lastIndexOf('/');
    const script = slash > 0 ? `mkdir -p ${relPath.slice(0, slash)} && cat > ${relPath}` : `cat > ${relPath}`;
    await this.exec(['shell', `run-as ${shellQuote(appId)} sh -c ${shellQuote(script)}`], { input: text });
    const written = await this.runAsCat(appId, relPath);
    if (written !== text) {
      throw new Error(
        `run-as write of ${relPath} for ${appId} did not store the expected content ` +
          `(${Buffer.byteLength(text)} bytes sent, ${Buffer.byteLength(written)} read back); does adb forward stdin?`,
      );
    }
  }

  /** `run-as <appId> rm -f <relPath>`. */
  async runAsRemove(appId: string, relPath: string): Promise<void> {
    checkRelPath(relPath);
    await this.shell(`run-as ${shellQuote(appId)} rm -f ${relPath}`);
  }

  /** `run-as <appId> ls <relDir>`: file names, empty when the directory does not exist. */
  async runAsList(appId: string, relDir: string): Promise<string[]> {
    checkRelPath(relDir);
    const result = await this.exec(['shell', `run-as ${shellQuote(appId)} ls ${relDir}`], { allowFailure: true });
    if (result.code !== 0) return [];
    return result.stdout
      .replace(/\r\n/g, '\n')
      .split('\n')
      .map((line) => line.trim())
      .filter((line) => line !== '');
  }

  async pull(remote: string, local: string): Promise<void> {
    await this.exec(['pull', remote, local], { timeoutMs: 300_000 });
  }

  /** `adb bugreport <zip>` (up to 15 minutes). */
  async bugreport(localZip: string): Promise<void> {
    await this.exec(['bugreport', localZip], { timeoutMs: 900_000 });
  }

  /** `adb exec-out screencap -p` into [localPng]. */
  async screenshot(localPng: string): Promise<void> {
    const result = await this.execRaw(['exec-out', 'screencap', '-p'], { timeoutMs: 30_000 });
    await writeFile(localPng, result.stdout);
  }

  // ---- location

  /** Location services on/off (`cmd location set-location-enabled`, falling back to `settings put secure location_mode`). */
  /**
   * Switches the device location setting and checks that it changed. `cmd location set-location-enabled` exists from
   * API 30; on API 29 its answer ("Can't find service: location") passed as success and location stayed on (P-P06 on
   * the first API 29 run). So each way is tried until [isLocationEnabled] reports the wanted state: `cmd location`,
   * the `location_mode` setting, then the older `location_providers_allowed` setting.
   */
  async setLocationEnabled(enabled: boolean): Promise<void> {
    const attempts = [
      `cmd location set-location-enabled ${enabled}`,
      `settings put secure location_mode ${enabled ? 3 : 0}`,
      `settings put secure location_providers_allowed ${enabled ? '+gps,+network' : '-gps,-network'}`,
    ];
    for (const command of attempts) {
      await this.exec(['shell', command], { allowFailure: true });
      const changed = await waitUntil(async () => (await this.isLocationEnabled()) === enabled, {
        timeoutMs: 5000,
        intervalMs: 500,
        message: `location ${enabled ? 'on' : 'off'}`,
      }).then(
        () => true,
        () => false,
      );
      if (changed) return;
    }
    throw new Error(`could not switch location ${enabled ? 'on' : 'off'}; tried: ${attempts.join(' | ')}`);
  }

  async isLocationEnabled(): Promise<boolean> {
    const result = await this.exec(['shell', 'cmd location is-location-enabled'], { allowFailure: true });
    const text = result.stdout.trim();
    if (result.code === 0 && (text === 'true' || text === 'false')) return text === 'true';
    const mode = (await this.shell('settings get secure location_mode')).trim();
    return mode !== '' && mode !== '0' && mode !== 'null';
  }

  /** Emulator GPS fix: `adb emu geo fix <lon> <lat> [alt [satellites]]` (note: the console takes longitude first). */
  async geoFix(lat: number, lon: number, options: { altitude?: number; satellites?: number } = {}): Promise<void> {
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) throw new Error(`geoFix needs finite coordinates, got ${lat}, ${lon}`);
    const parts = ['geo', 'fix', lon.toFixed(7), lat.toFixed(7)];
    if (options.altitude !== undefined || options.satellites !== undefined) parts.push(String(options.altitude ?? 0));
    if (options.satellites !== undefined) parts.push(String(Math.round(options.satellites)));
    await this.emu(parts.join(' '));
  }

  /**
   * Replays [points] with [playRoute] again and again until [RouteOptions.signal] aborts (the returned promise then
   * resolves; it rejects only on adb errors). For scenarios that need movement while other steps run.
   */
  async playRouteLoop(points: readonly LatLon[], options: RouteOptions & { signal: AbortSignal }): Promise<void> {
    while (!options.signal.aborted) {
      try {
        await this.playRoute(points, options);
      } catch (error) {
        if (options.signal.aborted) return;
        throw error;
      }
    }
  }

  /**
   * Replays [points] as geo fixes, interpolated at [RouteOptions.speedMps] every [RouteOptions.intervalMs] (see
   * [interpolateRoute]); the fixes are sent on a fixed schedule from the start time, so adb latency does not slow the
   * replay down. Resolves after the last point.
   */
  async playRoute(points: readonly LatLon[], options: RouteOptions = {}): Promise<void> {
    const speedMps = options.speedMps ?? 10;
    const intervalMs = options.intervalMs ?? 1000;
    const fixes = interpolateRoute(points, (speedMps * intervalMs) / 1000);
    const start = Date.now();
    for (let i = 0; i < fixes.length; i++) {
      if (options.signal?.aborted) throw options.signal.reason;
      const wait = start + i * intervalMs - Date.now();
      if (wait > 0) await sleep(wait, options.signal);
      const fix = fixes[i]!;
      await this.geoFix(fix.lat, fix.lon, fix.alt !== undefined ? { altitude: fix.alt } : {});
    }
  }

  private async requireApi(min: number, what: string): Promise<void> {
    const api = await this.apiLevel();
    if (api < min) throw new Error(`${what} needs API ${min} or newer (device API ${api})`);
  }

  /**
   * `cmd location providers add-test-provider <provider>` + `set-test-provider-enabled <provider> true` (API 31+;
   * rejects with a clear error below): subsequent fixes of that provider are mock locations. Also allows the shell's
   * MOCK_LOCATION app op first (best effort).
   */
  async addTestProvider(provider = 'gps'): Promise<void> {
    await this.requireApi(31, 'test location providers (cmd location providers)');
    await this.exec(['shell', 'appops set com.android.shell MOCK_LOCATION allow'], { allowFailure: true });
    await this.mockLocationCommand(`cmd location providers add-test-provider ${shellQuote(provider)}`);
    await this.mockLocationCommand(`cmd location providers set-test-provider-enabled ${shellQuote(provider)} true`);
  }

  /**
   * Runs a `cmd location providers …` test-provider command as the shell user. With `adb root` the shell is uid 0,
   * whose calls are attributed to the package `android`, which lacks MOCK_LOCATION ("android from uid 0 not allowed to
   * perform MOCK_LOCATION", CI API 34); `su shell` runs it as uid 2000 (`com.android.shell`, allowed above), which is
   * also how it runs on a device without root. Falls back to the plain command when `su` is missing.
   */
  private async mockLocationCommand(command: string): Promise<string> {
    if (await this.isRoot().catch(() => false)) {
      try {
        return await this.shellChecked(`su shell ${command}`);
      } catch (error) {
        if (!/su: not found|inaccessible or not found|No such file/i.test(error instanceof Error ? error.message : String(error))) {
          throw error;
        }
      }
    }
    return this.shellChecked(command);
  }

  /**
   * Waits (root only) until the device's persisted runtime-permission file lists every one of [permissions] as granted
   * for [appId]. Android writes permission changes to disk in the background; on the CI emulator a reboot about 15-20 s
   * after `pm grant` came back without the grants (P-L08, P-P11: the boot restore recorded `permission_denied`), which
   * a phone never sees because its permissions are granted long before a reboot. Resolves true once the file shows the
   * grants, false without root (nothing is read). Rejects when [timeoutMs] passes first, with what the last read found.
   */
  async waitForPersistedPermissions(
    appId: string,
    permissions: readonly string[],
    options: { timeoutMs?: number; intervalMs?: number } = {},
  ): Promise<boolean> {
    if (permissions.length === 0 || !(await this.isRoot().catch(() => false))) return false;
    const path =
      (await this.apiLevel()) >= 30
        ? '/data/misc_de/0/apexdata/com.android.permission/runtime-permissions.xml'
        : '/data/system/users/0/runtime-permissions.xml';
    // Android 12+ stores it as binary XML (ABX); abx2xml converts it, and a text file makes abx2xml fail, so cat it.
    const read = `abx2xml ${path} - 2>/dev/null || cat ${path}`;
    const timeoutMs = options.timeoutMs ?? 90_000;
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const out = await this.exec(['shell', read], { allowFailure: true, timeoutMs: 30_000 });
      if (persistedPermissionsGranted(out.stdout, appId, permissions)) return true;
      if (Date.now() >= deadline) {
        const section = packageSection(out.stdout, appId);
        throw new Error(
          `${path} did not list ${permissions.join(', ')} as granted for ${appId} within ${timeoutMs / 1000} s ` +
            `(read ${out.stdout.length} characters; ` +
            `${section === undefined ? 'no section for the app' : `app section: ${tail(section, 600)}`})`,
        );
      }
      await sleep(options.intervalMs ?? 2000);
    }
  }

  /** `cmd location providers set-test-provider-location <provider> --location <lat>,<lon> [--accuracy <m>]` (API 31+). */
  async setTestLocation(provider: string, lat: number, lon: number, accuracy?: number): Promise<void> {
    await this.requireApi(31, 'test location providers (cmd location providers)');
    let command = `cmd location providers set-test-provider-location ${shellQuote(provider)} --location ${lat.toFixed(7)},${lon.toFixed(7)}`;
    if (accuracy !== undefined) command += ` --accuracy ${accuracy}`;
    await this.mockLocationCommand(command);
  }

  /** `cmd location providers remove-test-provider <provider>`; a provider that does not exist and API < 31 are no-ops. */
  async removeTestProvider(provider = 'gps'): Promise<void> {
    if ((await this.apiLevel()) < 31) return;
    await this.mockLocationCommand(`cmd location providers remove-test-provider ${shellQuote(provider)}`).catch(() => undefined);
  }

  /**
   * `adb shell <command>` that also rejects when the output matches [failure] (default [COMMAND_FAILED]): many Android
   * shell commands report errors on stdout with exit code 0.
   */
  async shellChecked(command: string, failure: RegExp = COMMAND_FAILED): Promise<string> {
    const out = await this.shell(command);
    if (failure.test(out)) throw new Error(`${command} failed: ${tail(out)}`);
    return out;
  }

  // ---- power

  /** `dumpsys battery unplug` (required before forcing Doze). */
  async batteryUnplug(): Promise<void> {
    await this.shell('dumpsys battery unplug');
  }

  /** `dumpsys battery reset`. */
  async batteryReset(): Promise<void> {
    await this.shell('dumpsys battery reset');
  }

  /**
   * `dumpsys battery set status <n>`: 1 unknown, 2 charging, 3 discharging, 4 not charging, 5 full (names accepted).
   * Undone by [batteryReset].
   */
  async batterySetStatus(status: number | 'unknown' | 'charging' | 'discharging' | 'not_charging' | 'full'): Promise<void> {
    const codes = { unknown: 1, charging: 2, discharging: 3, not_charging: 4, full: 5 } as const;
    const code = typeof status === 'number' ? status : codes[status];
    if (!Number.isInteger(code) || code < 1 || code > 5) throw new Error(`battery status must be 1..5, got ${String(status)}`);
    await this.shell(`dumpsys battery set status ${code}`);
  }

  /** `dumpsys battery set level <0..100>`. */
  async batterySetLevel(level: number): Promise<void> {
    if (!Number.isInteger(level) || level < 0 || level > 100) throw new Error(`battery level must be an integer 0..100, got ${level}`);
    await this.shell(`dumpsys battery set level ${level}`);
  }

  // ---- connectivity

  /**
   * `cmd connectivity airplane-mode enable|disable` (API 30+); falls back to `settings put global airplane_mode_on`
   * plus the `AIRPLANE_MODE` broadcast (best effort) when the command is missing.
   */
  async setAirplaneMode(on: boolean): Promise<void> {
    const result = await this.exec(['shell', `cmd connectivity airplane-mode ${on ? 'enable' : 'disable'}`], { allowFailure: true });
    if (result.code === 0 && !COMMAND_FAILED.test(result.stdout + result.stderr)) return;
    await this.shell(`settings put global airplane_mode_on ${on ? 1 : 0}`);
    await this.exec(['shell', `am broadcast -a android.intent.action.AIRPLANE_MODE --ez state ${on}`], { allowFailure: true });
  }

  /** `svc wifi enable|disable`. */
  async setWifi(on: boolean): Promise<void> {
    await this.shell(`svc wifi ${on ? 'enable' : 'disable'}`);
  }

  /** `svc data enable|disable`. */
  async setData(on: boolean): Promise<void> {
    await this.shell(`svc data ${on ? 'enable' : 'disable'}`);
  }

  // ---- time

  /** `settings put global auto_time 0|1` and `auto_time_zone 0|1`. */
  async setAutoTime(on: boolean): Promise<void> {
    await this.shell(`settings put global auto_time ${on ? 1 : 0}`);
    await this.shell(`settings put global auto_time_zone ${on ? 1 : 0}`);
  }

  /**
   * (root) sets the wall clock to [epochMs] rounded to the second, with toybox `date -u MMDDhhmmCCYY.ss`
   * (elapsedRealtime is unaffected); call setAutoTime(false) first. Rejects without root or when the device clock is
   * more than 5 s off afterwards.
   */
  async setTime(epochMs: number): Promise<void> {
    if (!(await this.isRoot())) throw new Error('setTime needs adb root (a userdebug / "Google APIs" emulator image)');
    const date = new Date(Math.round(epochMs / 1000) * 1000);
    const pad = (value: number) => String(value).padStart(2, '0');
    const stamp =
      `${pad(date.getUTCMonth() + 1)}${pad(date.getUTCDate())}${pad(date.getUTCHours())}${pad(date.getUTCMinutes())}` +
      `${date.getUTCFullYear()}.${pad(date.getUTCSeconds())}`;
    await this.shell(`date -u ${stamp}`);
    const now = await this.deviceTime();
    if (Math.abs(now - date.getTime()) > 5000) {
      throw new Error(`setTime: the device clock is ${new Date(now).toISOString()} after setting ${date.toISOString()}`);
    }
  }

  /**
   * Device time zone, e.g. 'Asia/Riyadh': `cmd alarm set-timezone`, falling back to (root) `setprop
   * persist.sys.timezone`; rejects when `persist.sys.timezone` does not show the new zone afterwards. For a UTC zone
   * ('GMT', 'UTC', 'Etc/UTC', 'Etc/GMT') any of these names or an empty property counts as set (images without a
   * configured zone leave the property empty).
   */
  async setTimezone(tz: string): Promise<void> {
    const utcNames = ['GMT', 'UTC', 'Etc/UTC', 'Etc/GMT', 'Etc/GMT+0', 'Etc/GMT0'];
    const isSet = (value: string) => value === tz || (utcNames.includes(tz) && (value === '' || utcNames.includes(value)));
    await this.exec(['shell', `cmd alarm set-timezone ${shellQuote(tz)}`], { allowFailure: true });
    if (isSet(await this.getprop('persist.sys.timezone'))) return;
    if (await this.isRoot()) await this.shell(`setprop persist.sys.timezone ${shellQuote(tz)}`);
    const now = await this.getprop('persist.sys.timezone');
    if (!isSet(now)) throw new Error(`setTimezone(${tz}) failed: persist.sys.timezone is '${now}'`);
  }

  /** Device wall clock, epoch ms (`date +%s%N`, falling back to `date +%s` × 1000 where %N is not supported). */
  async deviceTime(): Promise<number> {
    const out = (await this.shell('date +%s%N')).trim();
    if (/^\d{16,}$/.test(out)) return Number(BigInt(out) / 1_000_000n);
    const seconds = (await this.shell('date +%s')).trim();
    if (!/^\d+$/.test(seconds)) throw new Error(`could not read the device time: '${out}' / '${seconds}'`);
    return Number.parseInt(seconds, 10) * 1000;
  }

  // ---- UI

  /** `settings put system font_scale <scale>`: a configuration change that recreates the activity. */
  async setFontScale(scale: number): Promise<void> {
    await this.shell(`settings put system font_scale ${scale}`);
  }

  /** KEYCODE_HOME: the app goes to the background (activity stopped, not destroyed). */
  async keyHome(): Promise<void> {
    await this.shell('input keyevent KEYCODE_HOME');
  }

  /** KEYCODE_SLEEP. */
  async screenOff(): Promise<void> {
    await this.shell('input keyevent KEYCODE_SLEEP');
  }

  /** KEYCODE_WAKEUP + `wm dismiss-keyguard`. */
  async screenOn(): Promise<void> {
    await this.shell('input keyevent KEYCODE_WAKEUP');
    await this.exec(['shell', 'wm dismiss-keyguard'], { allowFailure: true });
  }
}

/**
 * True when the runtime-permission XML [xml] has a section for [appId] in which every one of [permissions] is granted
 * (attribute order does not matter). Two formats: Android 10 (`/data/system/users/0/runtime-permissions.xml`) writes
 * `<pkg name=…>` with `<item name=… granted="true" …/>`; the permission module of Android 11+ writes
 * `<package name=…>` with `<permission name=… granted="true" …/>`.
 */
export function persistedPermissionsGranted(xml: string, appId: string, permissions: readonly string[]): boolean {
  const section = packageSection(xml, appId);
  if (section === undefined) return false;
  const tags = section.match(/<(?:item|perm|permission)\b[^>]*>/g) ?? [];
  return permissions.every((permission) =>
    tags.some((tag) => tag.includes(`name="${permission}"`) && /\bgranted="true"/.test(tag)),
  );
}

/** The `<pkg name="[appId]">…</pkg>` or `<package name="[appId]">…</package>` section of [xml], or undefined. */
function packageSection(xml: string, appId: string): string | undefined {
  const open = new RegExp(`<(pkg|package)\\s[^>]*\\bname="${appId.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}"`);
  const match = open.exec(xml);
  if (!match) return undefined;
  const end = xml.indexOf(`</${match[1]}>`, match.index);
  return xml.slice(match.index, end < 0 ? undefined : end);
}
