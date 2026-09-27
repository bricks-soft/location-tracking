// Crash scanner of the e2e kit (Unit 7).
import { AssertionError } from 'node:assert';
import type { Adb } from './adb.ts';
import { parseLogcat, type LogcatLine } from './logcat.ts';

export interface CrashReport {
  kind: 'crash' | 'anr' | 'native';
  /** process name */
  process: string;
  pid?: number;
  /** exception class (crash), ANR reason, or the signal (native) */
  exception: string;
  /** the full logcat excerpt */
  text: string;
  /** time of the first line as printed by logcat */
  time?: string;
}

/** Texts that identify a foreground service that did not call startForeground() in time (API 31+ and older). */
export const FGS_DID_NOT_START_PATTERNS: readonly RegExp[] = [
  /ForegroundServiceDidNotStartInTimeException/,
  /Context\.startForegroundService\(\) did not then call Service\.startForeground\(\)/,
];

/** Texts of `RemoteServiceException` crashes (the system crashes the app for a service it mishandled). */
const REMOTE_SERVICE_EXCEPTION = /RemoteServiceException/;

function belongsTo(processName: string, appId: string): boolean {
  return processName === appId || processName.startsWith(`${appId}:`);
}

/**
 * Maps a kernel thread name (`/proc/<pid>/comm`, which Zygote sets to the LAST 15 characters of the process name) back
 * to the process name of [appId], or undefined when it is not one of the app's processes. Names shorter than 15
 * characters are complete and must match exactly.
 */
export function appProcessFromComm(comm: string, appId: string): string | undefined {
  if (belongsTo(comm, appId)) return comm;
  if (comm.length !== 15) return undefined;
  if (appId.endsWith(comm)) return appId;
  const colon = comm.indexOf(':');
  if (colon >= 0 && appId.endsWith(comm.slice(0, colon))) return `${appId}${comm.slice(colon)}`;
  return undefined;
}

/**
 * Identity of a report across scans. Native crashes are keyed by pid only: they are found through two different lines
 * (libc in the main buffer, debuggerd in the crash buffer) that rotate out at different times.
 */
function reportKey(report: CrashReport): string {
  if (report.kind === 'native') return `native|${report.pid ?? report.process}`;
  return `${report.kind}|${report.process}|${report.pid ?? ''}|${report.time ?? ''}|${report.exception}`;
}

/**
 * Parses crash, ANR and native-crash reports of [appId] (and its `appId:*` processes) from logcat threadtime text:
 * - Java crashes: the `AndroidRuntime` block that starts with `FATAL EXCEPTION` and names `Process: <name>, PID: <pid>`;
 *   [CrashReport.exception] is the first exception class of the block (e.g.
 *   `android.app.RemoteServiceException$ForegroundServiceDidNotStartInTimeException`);
 * - ANRs: the `ActivityManager` block that starts with `ANR in <process>`; exception = the `Reason:` line;
 * - native crashes: `libc` `Fatal signal ... pid <pid> (<name>)` (the name is the kernel thread name, at most the
 *   last 15 characters; see [appProcessFromComm]) and debuggerd `DEBUG` `>>> <name> <<<` lines (one report per pid).
 */
export function parseCrashReports(text: string, appId: string): CrashReport[] {
  const lines = parseLogcat(text);
  const reports: CrashReport[] = [];
  const nativePids = new Set<number>();
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]!;
    if (line.tag === 'AndroidRuntime' && /FATAL EXCEPTION/.test(line.message)) {
      const block = collectBlock(lines, i, (next) => next.tag === 'AndroidRuntime' && next.pid === line.pid, 200);
      const processLine = block.map((l) => /Process: ([^,\s]+), PID: (\d+)/.exec(l.message)).find((m) => m !== null);
      const processName = processLine?.[1] ?? '';
      if (!belongsTo(processName, appId)) continue;
      const exceptionLine = block.slice(1).find((l) => /^[\w$.]+(Exception|Error|Throwable)\b/.test(l.message.trim()));
      const exception = exceptionLine ? /^([\w$.]+)/.exec(exceptionLine.message.trim())![1]! : 'unknown';
      reports.push({
        kind: 'crash',
        process: processName,
        pid: Number(processLine?.[2] ?? line.pid),
        exception,
        text: block.map((l) => l.raw).join('\n'),
        time: line.time,
      });
      continue;
    }
    const anr = line.tag === 'ActivityManager' ? /ANR in (\S+)/.exec(line.message) : null;
    if (anr && belongsTo(anr[1]!, appId)) {
      const block = collectBlock(lines, i, (next) => next.tag === 'ActivityManager' && next.pid === line.pid, 60);
      const pidLine = block.map((l) => /^\s*PID: (\d+)/.exec(l.message)).find((m) => m !== null);
      const reasonLine = block.map((l) => /^\s*Reason: (.*)$/.exec(l.message)).find((m) => m !== null);
      reports.push({
        kind: 'anr',
        process: anr[1]!,
        ...(pidLine ? { pid: Number(pidLine[1]) } : {}),
        exception: reasonLine?.[1]?.trim() ?? 'ANR',
        text: block.map((l) => l.raw).join('\n'),
        time: line.time,
      });
      continue;
    }
    const libc = line.tag === 'libc' ? /Fatal signal (\d+ \([A-Z]+\)).*?pid (\d+) \(([^)]+)\)/.exec(line.message) : null;
    const debuggerd = line.tag === 'DEBUG' ? /pid: (\d+), tid: \d+, name: .*>>> (\S+) <<</.exec(line.message) : null;
    if (libc || debuggerd) {
      const pid = Number(libc ? libc[2] : debuggerd![1]);
      const processName = libc ? appProcessFromComm(libc[3]!, appId) : belongsTo(debuggerd![2]!, appId) ? debuggerd![2]! : undefined;
      if (processName === undefined || nativePids.has(pid)) continue;
      nativePids.add(pid);
      const signalLine = libc ? undefined : lines.slice(i, i + 10).find((l) => l.tag === 'DEBUG' && /^signal \d+/.test(l.message));
      reports.push({
        kind: 'native',
        process: processName,
        pid,
        exception: libc ? `signal ${libc[1]}` : (/^signal (\d+ \([A-Z]+\))/.exec(signalLine?.message ?? '')?.[0] ?? 'native crash'),
        text: line.raw,
        time: line.time,
      });
    }
  }
  return reports;
}

/** The consecutive lines from [start] that satisfy [sameBlock] (other lines in between are skipped), at most [max]. */
function collectBlock(lines: readonly LogcatLine[], start: number, sameBlock: (line: LogcatLine) => boolean, max: number): LogcatLine[] {
  const first = lines[start]!;
  const block = [first];
  let misses = 0;
  for (let j = start + 1; j < lines.length && block.length < max; j++) {
    const next = lines[j]!;
    if (sameBlock(next)) {
      block.push(next);
      misses = 0;
    } else if (++misses > 20) {
      break;
    }
  }
  return block;
}

/**
 * Lines of [text] that report a foreground service of [appId] that did not start in time: they match
 * [FGS_DID_NOT_START_PATTERNS] and either name [appId] or belong to a crash report of [appId].
 */
export function findFgsDidNotStartLines(text: string, appId: string): string[] {
  const crashText = parseCrashReports(text, appId)
    .map((report) => report.text)
    .join('\n');
  const out: string[] = [];
  for (const raw of text.split('\n')) {
    const line = raw.replace(/\r$/, '');
    if (!FGS_DID_NOT_START_PATTERNS.some((pattern) => pattern.test(line))) continue;
    if (line.includes(appId) || crashText.includes(line)) out.push(line);
  }
  return out;
}

/**
 * Scans logcat (crash, main and system buffers) for crashes and ANRs of one app since [mark].
 *
 * "Since the mark" does not rely on the device clock alone (scenarios move the wall clock): [mark] records the device
 * time and the reports and matching lines already in the buffers, and later scans report only what was not there.
 * Nothing is cleared.
 */
export class CrashScanner {
  readonly adb: Adb;
  readonly appId: string;
  /** device wall clock at the last [mark], epoch ms */
  markedAt: number | undefined;
  private baselineReports = new Set<string>();
  private baselineFgsLines = new Set<string>();

  constructor(adb: Adb, appId: string) {
    this.adb = adb;
    this.appId = appId;
  }

  private dump(): Promise<string> {
    return this.adb.logcat.dump({ buffers: ['crash', 'main', 'system'] });
  }

  /** Remembers the device time and what the buffers already contain; later scans only report newer entries. */
  async mark(): Promise<void> {
    this.markedAt = await this.adb.deviceTime();
    const text = await this.dump();
    this.baselineReports = new Set(parseCrashReports(text, this.appId).map(reportKey));
    this.baselineFgsLines = new Set(findFgsDidNotStartLines(text, this.appId));
  }

  /** `FATAL EXCEPTION` / `ANR in` / native crash entries of [appId] since the mark. */
  async crashes(): Promise<CrashReport[]> {
    const text = await this.dump();
    return parseCrashReports(text, this.appId).filter((report) => !this.baselineReports.has(reportKey(report)));
  }

  /** Fails with every crash report since the mark. */
  async assertNoCrash(): Promise<void> {
    const reports = await this.crashes();
    if (reports.length === 0) return;
    const details = reports.map((r) => `- ${r.kind} of ${r.process}${r.pid !== undefined ? ` (pid ${r.pid})` : ''}: ${r.exception}\n${r.text}`);
    throw new AssertionError({ message: `${this.appId} crashed ${reports.length} time(s) since the mark:\n${details.join('\n')}` });
  }

  /**
   * Fails if logcat shows `ForegroundServiceDidNotStartInTimeException` (or the pre-12 text "Context.startForegroundService()
   * did not then call Service.startForeground()") for [appId] since the mark, crash or not. `RemoteServiceException`
   * crashes of the app also fail it.
   */
  async assertNoFgsDidNotStartInTime(): Promise<void> {
    const text = await this.dump();
    const lines = findFgsDidNotStartLines(text, this.appId).filter((line) => !this.baselineFgsLines.has(line));
    const remote = parseCrashReports(text, this.appId).filter(
      (report) => !this.baselineReports.has(reportKey(report)) && REMOTE_SERVICE_EXCEPTION.test(report.exception),
    );
    if (lines.length === 0 && remote.length === 0) return;
    const parts = [...lines, ...remote.map((r) => `${r.process} (pid ${r.pid ?? '?'}): ${r.exception}`)];
    throw new AssertionError({
      message: `${this.appId}: a foreground service did not start in time since the mark:\n${parts.join('\n')}`,
    });
  }
}
