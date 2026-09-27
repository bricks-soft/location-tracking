// STUB — owned by Unit 7 (e2e-kit). Protocol: docs/e2e/architecture.md §6 (debug hook contract).
import type { Adb } from './adb.ts';
import type {
  GeofenceJson,
  HeartbeatStatusJson,
  Premise,
  PremiseAuditEntry,
  PremiseStatus,
  StateJson,
  WireRecord,
} from './types.ts';
import { notImplemented } from './util.ts';

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

/** A command answered with `ok:false`; [code] is the plugin `ErrorCode` (or `BAD_COMMAND` / `INTERNAL`). */
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

/**
 * Sends debug commands to an example app: `am broadcast -a <appId>.E2E -n <appId>/.e2e.E2eCommandReceiver
 * --include-stopped-packages --es id <uuid> --es cmd <cmd> --es json <args>`, then waits for the `LT-E2E` logcat line
 * with the same id. A result too large for one logcat line is read from `resultFile` with `run-as`.
 */
export class E2eCommands {
  readonly adb: Adb;
  readonly appId: string;

  constructor(adb: Adb, appId: string) {
    this.adb = adb;
    this.appId = appId;
  }

  /** Sends [cmd] with [args] (JSON); resolves with `result`, rejects with [E2eCommandError] on `ok:false`. */
  async send<T = unknown>(cmd: E2eCommandName, args?: Record<string, unknown>, options?: SendOptions): Promise<T> {
    return notImplemented('E2eCommands.send');
  }

  // ---- typed helpers (each is send() with the documented args and result)

  async ready(config?: Record<string, unknown>, reset = true): Promise<StateJson> {
    return notImplemented('E2eCommands.ready');
  }

  async setConfig(config: Record<string, unknown>): Promise<StateJson> {
    return notImplemented('E2eCommands.setConfig');
  }

  async start(options?: SendOptions): Promise<StateJson> {
    return notImplemented('E2eCommands.start');
  }

  async startGeofences(): Promise<StateJson> {
    return notImplemented('E2eCommands.startGeofences');
  }

  async stop(): Promise<StateJson> {
    return notImplemented('E2eCommands.stop');
  }

  async changePace(isMoving: boolean): Promise<void> {
    return notImplemented('E2eCommands.changePace');
  }

  async state(): Promise<StateJson> {
    return notImplemented('E2eCommands.state');
  }

  async heartbeatStatus(): Promise<HeartbeatStatusJson> {
    return notImplemented('E2eCommands.heartbeatStatus');
  }

  /** Result: the uploaded records. */
  async sync(): Promise<WireRecord[]> {
    return notImplemented('E2eCommands.sync');
  }

  /** Result: the new record's uuid. */
  async insertLocation(location: Record<string, unknown>): Promise<string> {
    return notImplemented('E2eCommands.insertLocation');
  }

  async addGeofence(geofence: GeofenceJson): Promise<void> {
    return notImplemented('E2eCommands.addGeofence');
  }

  async removeGeofence(identifier: string): Promise<void> {
    return notImplemented('E2eCommands.removeGeofence');
  }

  async getGeofences(): Promise<GeofenceJson[]> {
    return notImplemented('E2eCommands.getGeofences');
  }

  /** Logs its line, then sleeps the main thread for [ms] (after [delayMs]). */
  async blockMainThread(ms: number, delayMs = 0): Promise<void> {
    return notImplemented('E2eCommands.blockMainThread');
  }

  // ---- field-force only

  async premiseStart(premise: Premise, auditUrl?: string): Promise<PremiseStatus> {
    return notImplemented('E2eCommands.premiseStart');
  }

  async premiseStop(): Promise<PremiseStatus> {
    return notImplemented('E2eCommands.premiseStop');
  }

  async premiseStatus(): Promise<PremiseStatus> {
    return notImplemented('E2eCommands.premiseStatus');
  }

  async premiseAuditLog(limit?: number): Promise<PremiseAuditEntry[]> {
    return notImplemented('E2eCommands.premiseAuditLog');
  }
}
