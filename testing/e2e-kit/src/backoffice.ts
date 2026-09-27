// STUB — owned by Unit 7 (e2e-kit). HTTP contract: docs/e2e/architecture.md §7 (mock back office).
import { EMULATOR_HOST } from './env.ts';
import type { PremiseAuditEntry, RecordEvent, WireRecord } from './types.ts';
import { notImplemented, type WaitOptions } from './util.ts';

/** One record received on `POST /locations` (single, batch or rootProperty bodies are all split into records). */
export interface StoredRecord {
  /** host epoch ms when the request arrived */
  receivedAt: number;
  requestId: number;
  path: string;
  /** position of the record in its request body */
  index: number;
  /** number of records in the same request (1 = single-record body) */
  batchSize: number;
  record: WireRecord;
  /** root keys of the body other than the record(s): the plugin's `http.params` */
  params: Record<string, unknown>;
  /** the request's Authorization header, if any */
  authorization?: string;
}

export interface RecordFilter {
  event?: RecordEvent | readonly RecordEvent[];
  /** receivedAt >= since (host epoch ms) */
  since?: number;
  uuid?: string;
  /** keep only the first receipt of each uuid */
  unique?: boolean;
}

/** One PremiseMonitor audit entry received on `POST /premise-audit`. */
export interface StoredPremiseEntry {
  receivedAt: number;
  requestId: number;
  deviceId?: string;
  entry: PremiseAuditEntry;
}

export interface PremiseFilter {
  since?: number;
  kind?: PremiseAuditEntry['kind'];
  /** premise entries of this type */
  type?: string;
  /** record entries of this record event, or event entries of this name */
  event?: string;
}

/** Every request the server answered (including control endpoints). */
export interface RequestLogEntry {
  id: number;
  method: string;
  path: string;
  /** raw query string without '?' */
  query: string;
  receivedAt: number;
  /** answered status; 0 = dropped */
  status: number;
  headers: Record<string, string>;
  /** request body as text (multipart uploads: a size summary) */
  body: string;
  /** set when a fault answered the request */
  fault?: 'status' | 'drop' | 'delay';
}

/**
 * A fault for the next [count] requests to [path] (default 1; -1 = until reset): answer [status] (default 500), or
 * [drop] the connection without an answer, after [delayMs]. A fault with only [delayMs] delays and then answers
 * normally. Faulted requests (status/drop) store nothing.
 */
export interface Fault {
  path: string;
  status?: number;
  count?: number;
  delayMs?: number;
  drop?: boolean;
}

export interface MockBackOfficeOptions {
  /** default E2E_BACKEND_PORT or 8787; 0 = any free port */
  port?: number;
  /** default '0.0.0.0' (the emulator reaches the host at 10.0.2.2) */
  host?: string;
  /** receives one line per request (also kept for Artifacts) */
  log?: (line: string) => void;
}

/**
 * In-process mock back office (node:http). Endpoints:
 * - `POST /locations`: plugin uploads; stores every record of single, batch and rootProperty bodies.
 * - `POST /premise-audit`: PremiseMonitor uploads (`{device_id?, entries: PremiseAuditEntry[]}`).
 * - `POST /auth/refresh`: JWT refresh; answers `{accessToken: 'e2e-access-<n>', refreshToken: 'e2e-refresh-<n>',
 *   expires_in: 3600}`.
 * - `POST /logs`: `uploadLog()` multipart bodies.
 * - Control (JSON): `GET /__records?event=&since=`, `GET /__premise?since=&kind=&type=`, `GET /__requests?path=&since=`,
 *   `POST /__faults` (a [Fault]), `DELETE /__faults`, `POST /__reset`, `GET /__health`.
 * The same server runs standalone for agents and curl: `npm run backoffice -- --port 8787` in testing/e2e-kit.
 */
export class MockBackOffice {
  readonly host: string;
  private configuredPort: number;

  constructor(options: MockBackOfficeOptions = {}) {
    this.host = options.host ?? '0.0.0.0';
    this.configuredPort = options.port ?? 8787;
  }

  /** The bound port (after start). */
  get port(): number {
    return this.configuredPort;
  }

  get running(): boolean {
    return notImplemented('MockBackOffice.running');
  }

  async start(): Promise<void> {
    return notImplemented('MockBackOffice.start');
  }

  async stop(): Promise<void> {
    return notImplemented('MockBackOffice.stop');
  }

  /** URL of [path] as the emulator sees it: `http://10.0.2.2:<port><path>`. */
  url(pathFromEmulator = '/locations'): string {
    return `http://${EMULATOR_HOST}:${this.port}${pathFromEmulator}`;
  }

  /** URL of [path] from the host: `http://127.0.0.1:<port><path>`. */
  hostUrl(path = '/'): string {
    return `http://127.0.0.1:${this.port}${path}`;
  }

  /** Received records in arrival order. */
  records(filter?: RecordFilter): StoredRecord[] {
    return notImplemented('MockBackOffice.records');
  }

  /** Received PremiseMonitor entries in arrival order. */
  premiseRecords(filter?: PremiseFilter): StoredPremiseEntry[] {
    return notImplemented('MockBackOffice.premiseRecords');
  }

  requests(filter?: { path?: string; since?: number }): RequestLogEntry[] {
    return notImplemented('MockBackOffice.requests');
  }

  /** Polls [predicate] (default every 500 ms) until it returns a truthy value; rejects after the timeout. */
  async waitFor<T>(predicate: (office: MockBackOffice) => T | undefined | null | false, options?: WaitOptions): Promise<T> {
    return notImplemented('MockBackOffice.waitFor');
  }

  setFault(fault: Fault): void {
    return notImplemented('MockBackOffice.setFault');
  }

  clearFaults(): void {
    return notImplemented('MockBackOffice.clearFaults');
  }

  /** Forgets records, premise entries, requests, faults and issued tokens. */
  reset(): void {
    return notImplemented('MockBackOffice.reset');
  }

  /** The request log as text (for Artifacts). */
  logText(): string {
    return notImplemented('MockBackOffice.logText');
  }
}
