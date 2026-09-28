// Debug-command client of the e2e kit (Unit 7). Protocol: docs/e2e/architecture.md §6 (debug hook contract).
import { randomBytes } from 'node:crypto';
import type { Adb } from './adb.ts';
import { parseLogcatLine, type LogcatLine } from './logcat.ts';
import type {
  GeofenceJson,
  HeartbeatStatusJson,
  Premise,
  PremiseAuditEntry,
  PremiseStatus,
  StateJson,
  WireRecord,
} from './types.ts';
import { shellQuote, sleep } from './util.ts';

/** Commands of both example apps' debug receivers; `premise.*` exist only in the field-force app. */
export type E2eCommandName =
  | 'ready'
  | 'setConfig'
  | 'start'
  | 'startGeofences'
  | 'stop'
  | 'changePace'
  | 'state'
  | 'heartbeatStatus'
  | 'sync'
  | 'insertLocation'
  | 'addGeofence'
  | 'removeGeofence'
  | 'otherAppLocation'
  | 'finishActivities'
  | 'getGeofences'
  | 'blockMainThread'
  | 'premise.start'
  | 'premise.stop'
  | 'premise.status'
  | 'premise.auditLog';

/** One `LT-E2E` logcat line, parsed. */
export type E2eResponse =
  | { id: string; cmd: string; ok: true; result: unknown; resultFile?: undefined }
  | { id: string; cmd: string; ok: true; resultFile: string; result?: undefined }
  | { id: string; cmd: string; ok: false; code: string; message: string };

/**
 * A command answered with `ok:false`; [code] is the plugin `ErrorCode` (or `BAD_COMMAND` / `TIMEOUT` / `INTERNAL` from
 * the receiver). The kit adds two codes of its own: `NO_RESPONSE` (no `LT-E2E` line with the request id within the
 * timeout: the receiver did not run, e.g. a release build or a wrong app id) and `BAD_RESULT_FILE` (the `resultFile`
 * could not be read or parsed).
 */
export class E2eCommandError extends Error {
  readonly code: string;
  readonly cmd: string;

  constructor(cmd: string, code: string, message: string) {
    super(`${cmd} failed: ${code}: ${message}`);
    this.name = 'E2eCommandError';
    this.cmd = cmd;
    this.code = code;
  }
}

export interface SendOptions {
  /** wait for the LT-E2E line (default 30000 ms) */
  timeoutMs?: number;
  /** `--receiver-foreground` (default false: a background broadcast, like a real background trigger) */
  foreground?: boolean;
}

/** Logcat tag of the receivers' response lines. */
export const E2E_LOG_TAG = 'LT-E2E';

let requestCounter = 0;

/** A request id unique across test processes: `e2e-<host pid>-<ms base36>-<counter>-<6 hex>` (matches `[A-Za-z0-9._-]{1,64}`). */
export function newRequestId(): string {
  requestCounter += 1;
  return `e2e-${process.pid}-${Date.now().toString(36)}-${requestCounter}-${randomBytes(3).toString('hex')}`;
}

/**
 * Finds the response line of request [id] in logcat output (threadtime lines of tag `LT-E2E`, or raw message lines),
 * with the parsed logcat line (undefined for a raw message line). Lines of other requests are ignored, so old lines
 * never match.
 */
export function findResponseLine(logcatText: string, id: string): { response: E2eResponse; line: LogcatLine | undefined } | undefined {
  for (const raw of logcatText.split('\n')) {
    if (!raw.includes(id)) continue;
    const parsed = parseLogcatLine(raw);
    if (parsed && parsed.tag !== E2E_LOG_TAG) continue;
    const message = parsed ? parsed.message : raw;
    const brace = message.indexOf('{');
    if (brace < 0) continue;
    try {
      const value = JSON.parse(message.slice(brace)) as Record<string, unknown>;
      if (value && typeof value === 'object' && value['id'] === id && typeof value['ok'] === 'boolean') {
        return { response: value as unknown as E2eResponse, line: parsed };
      }
    } catch {
      // not a JSON response line
    }
  }
  return undefined;
}

/** The response of request [id] in logcat output (see [findResponseLine]). */
export function findResponse(logcatText: string, id: string): E2eResponse | undefined {
  return findResponseLine(logcatText, id)?.response;
}

/** A command line prepared by [E2eCommands.broadcastLine]. */
export interface PreparedCommand {
  /** the request id the response line will carry */
  id: string;
  cmd: E2eCommandName;
  /** the `am broadcast ...` command for `adb shell`, quoted for the device shell */
  command: string;
}

/** A response with the logcat line it came from. */
export interface DetailedResult<T> {
  id: string;
  result: T;
  /** the parsed LT-E2E line; its `epochMs` is the device time when the receiver logged the response */
  line: LogcatLine | undefined;
}

function isResponseObject(value: unknown, id: string): value is E2eResponse {
  return (
    typeof value === 'object' &&
    value !== null &&
    !Array.isArray(value) &&
    (value as Record<string, unknown>)['id'] === id &&
    typeof (value as Record<string, unknown>)['ok'] === 'boolean'
  );
}

/**
 * Sends debug commands to an example app: `am broadcast -a <appId>.E2E -n <appId>/.e2e.E2eCommandReceiver
 * --include-stopped-packages --es id <id> --es cmd <cmd> --es json64 <base64 of the JSON args>` (through `adb shell`,
 * whose user holds android.permission.DUMP, which the plugin example's receiver requires), then polls
 * `logcat -d -e <id> LT-E2E:I *:S` for the line with the same id. Generated ids start with `e2e-`, so they never
 * equal the reserved ids `example` and `ff-overrides` (the receivers refuse those: a result file with that name would
 * overwrite a test-mode file). A result too large for one logcat line is read from
 * `resultFile` with `run-as <appId> cat` (the file may hold the full response object or only the result) and removed.
 */
export class E2eCommands {
  readonly adb: Adb;
  readonly appId: string;
  /** logcat polling interval while waiting for a response, ms */
  pollIntervalMs = 250;

  constructor(adb: Adb, appId: string) {
    this.adb = adb;
    this.appId = appId;
  }

  /**
   * The exact `am broadcast` command line of [cmd] with [args] and a new request id, for `adb shell` (e.g. to combine a
   * command with `am force-stop` in one shell call). Wait for its answer with [waitForResponse].
   */
  broadcastLine(cmd: E2eCommandName, args: Record<string, unknown> = {}, options: { foreground?: boolean } = {}): PreparedCommand {
    const id = newRequestId();
    const json64 = Buffer.from(JSON.stringify(args), 'utf8').toString('base64');
    const parts = ['am', 'broadcast', '-a', `${this.appId}.E2E`, '-n', `${this.appId}/.e2e.E2eCommandReceiver`, '--include-stopped-packages'];
    if (options.foreground) parts.push('--receiver-foreground');
    parts.push('--es', 'id', id, '--es', 'cmd', cmd, '--es', 'json64', json64);
    return { id, cmd, command: parts.map(shellQuote).join(' ') };
  }

  /** Sends [cmd] with [args] (JSON); resolves with `result`, rejects with [E2eCommandError] on `ok:false`. */
  async send<T = unknown>(cmd: E2eCommandName, args: Record<string, unknown> = {}, options: SendOptions = {}): Promise<T> {
    return (await this.sendDetailed<T>(cmd, args, options)).result;
  }

  /** [send], also returning the request id and the parsed LT-E2E line (device time of the response). */
  async sendDetailed<T = unknown>(cmd: E2eCommandName, args: Record<string, unknown> = {}, options: SendOptions = {}): Promise<DetailedResult<T>> {
    const timeoutMs = options.timeoutMs ?? 30_000;
    const prepared = this.broadcastLine(cmd, args, { foreground: options.foreground === true });
    const state: { error?: unknown; output?: string } = {};
    const broadcast = this.adb.shell(prepared.command, { timeoutMs: timeoutMs + 10_000 }).then(
      (out) => {
        state.output = out;
        if (/^Error|Exception/m.test(out)) state.error = new Error(`am broadcast ${this.appId}.E2E failed: ${out.trim()}`);
      },
      (error: unknown) => {
        state.error = error ?? new Error('am broadcast failed');
      },
    );
    try {
      return await this.pollResponse<T>(prepared.id, cmd, timeoutMs, state);
    } finally {
      // The receiver logs before PendingResult.finish(), so am broadcast returns right after; do not wait long for it.
      // The timer is unref'd so it does not keep the test process alive.
      await Promise.race([
        broadcast,
        new Promise<void>((resolve) => {
          setTimeout(resolve, 5000).unref();
        }),
      ]);
    }
  }

  /**
   * Waits for the response of a command sent with [broadcastLine] (default timeout 30 s); resolves like [sendDetailed],
   * rejects with [E2eCommandError] (`NO_RESPONSE` after the timeout).
   */
  async waitForResponse<T = unknown>(prepared: Pick<PreparedCommand, 'id' | 'cmd'>, options: { timeoutMs?: number } = {}): Promise<DetailedResult<T>> {
    return this.pollResponse<T>(prepared.id, prepared.cmd, options.timeoutMs ?? 30_000, {});
  }

  private async pollResponse<T>(
    id: string,
    cmd: string,
    timeoutMs: number,
    broadcast: { error?: unknown; output?: string },
  ): Promise<DetailedResult<T>> {
    const deadline = Date.now() + timeoutMs;
    let found: { response: E2eResponse; line: LogcatLine | undefined } | undefined;
    for (;;) {
      // `-e <id>`: logcat returns only this request's line, so the poll does not re-read earlier responses.
      const text = await this.adb.logcat.dump({ buffers: ['main'], regex: id, filters: [`${E2E_LOG_TAG}:I`, '*:S'] });
      found = findResponseLine(text, id);
      if (found) break;
      if (broadcast.error !== undefined) throw broadcast.error;
      if (Date.now() >= deadline) {
        const am = broadcast.output === undefined ? 'no am broadcast output yet' : `am broadcast printed: ${broadcast.output.trim()}`;
        throw new E2eCommandError(
          cmd,
          'NO_RESPONSE',
          `no ${E2E_LOG_TAG} line for request ${id} from ${this.appId} within ${timeoutMs} ms (${am}); ` +
            'is the debug build with the e2e receiver installed?',
        );
      }
      await sleep(this.pollIntervalMs);
    }
    const response = found.response;
    if (!response.ok) throw new E2eCommandError(cmd, response.code ?? 'INTERNAL', response.message ?? '');
    const result =
      typeof response.resultFile === 'string' ? await this.readResultFile(cmd, id, response.resultFile) : response.result;
    return { id, result: result as T, line: found.line };
  }

  private async readResultFile(cmd: string, id: string, resultFile: string): Promise<unknown> {
    let full: unknown;
    try {
      full = JSON.parse(await this.adb.runAsCat(this.appId, resultFile));
    } catch (error) {
      throw new E2eCommandError(cmd, 'BAD_RESULT_FILE', `could not read ${resultFile}: ${error instanceof Error ? error.message : String(error)}`);
    }
    await this.adb.runAsRemove(this.appId, resultFile).catch(() => {});
    if (isResponseObject(full, id)) {
      if (!full.ok) throw new E2eCommandError(cmd, full.code ?? 'INTERNAL', full.message ?? '');
      return full.result;
    }
    return full;
  }

  // ---- typed helpers (each is send() with the documented args and result)

  async ready(config?: Record<string, unknown>, reset = true): Promise<StateJson> {
    return this.send<StateJson>('ready', config === undefined ? { reset } : { config, reset });
  }

  async setConfig(config: Record<string, unknown>): Promise<StateJson> {
    return this.send<StateJson>('setConfig', { config });
  }

  async start(options?: SendOptions): Promise<StateJson> {
    return this.send<StateJson>('start', {}, options);
  }

  async startGeofences(): Promise<StateJson> {
    return this.send<StateJson>('startGeofences');
  }

  async stop(): Promise<StateJson> {
    return this.send<StateJson>('stop');
  }

  async changePace(isMoving: boolean): Promise<void> {
    await this.send('changePace', { isMoving });
  }

  async state(): Promise<StateJson> {
    return this.send<StateJson>('state');
  }

  async heartbeatStatus(): Promise<HeartbeatStatusJson> {
    return this.send<HeartbeatStatusJson>('heartbeatStatus');
  }

  /** Result: the uploaded records. */
  async sync(): Promise<WireRecord[]> {
    return this.send<WireRecord[]>('sync', {}, { timeoutMs: 60_000 });
  }

  /** Result: the new record's uuid. */
  async insertLocation(location: Record<string, unknown>): Promise<string> {
    const result = await this.send<{ uuid?: unknown }>('insertLocation', { location });
    if (!result || typeof result.uuid !== 'string') {
      throw new E2eCommandError('insertLocation', 'BAD_RESULT', `expected {uuid}, got ${JSON.stringify(result)}`);
    }
    return result.uuid;
  }

  async addGeofence(geofence: GeofenceJson): Promise<void> {
    await this.send('addGeofence', { geofence });
  }

  async removeGeofence(identifier: string): Promise<void> {
    await this.send('removeGeofence', { identifier });
  }

  async getGeofences(): Promise<GeofenceJson[]> {
    return this.send<GeofenceJson[]>('getGeofences');
  }

  /** Logs its line, then sleeps the main thread for [ms] (after [delayMs]). */
  async blockMainThread(ms: number, delayMs = 0): Promise<void> {
    await this.send('blockMainThread', { ms, delayMs });
  }

  /**
   * Plugin example only (test support): requests GPS updates from the app's process through `LocationManager`, the
   * way another app would, or stops them. The emulator produces fixes only while someone asks the GPS provider, so a
   * scenario that tests movement detection while the plugin has GPS off (passive updates + stationary geofence) turns
   * this on during its route replay. `dumpsys location` attributes the request to the app: scenarios that assert "no
   * GPS request" must keep it off. Resolves with the number of fixes the request received since it was turned on.
   */
  async otherAppLocation(enabled: boolean, intervalMs = 1000): Promise<{ enabled: boolean; intervalMs: number; fixes: number }> {
    return this.send('otherAppLocation', { enabled, intervalMs });
  }

  // ---- field-force only

  /**
   * Field-force only (test support): finishes the app's live activities on the main thread, so the activity and its
   * WebView are destroyed while the process keeps running. Resolves with the number of activities finished.
   */
  async finishActivities(): Promise<number> {
    const result = await this.send<{ finished: number }>('finishActivities', {});
    return result.finished;
  }

  async premiseStart(premise: Premise, auditUrl?: string): Promise<PremiseStatus> {
    return this.send<PremiseStatus>('premise.start', auditUrl === undefined ? { premise } : { premise, auditUrl });
  }

  async premiseStop(): Promise<PremiseStatus> {
    return this.send<PremiseStatus>('premise.stop');
  }

  async premiseStatus(): Promise<PremiseStatus> {
    return this.send<PremiseStatus>('premise.status');
  }

  async premiseAuditLog(limit?: number): Promise<PremiseAuditEntry[]> {
    return this.send<PremiseAuditEntry[]>('premise.auditLog', limit === undefined ? {} : { limit });
  }
}
