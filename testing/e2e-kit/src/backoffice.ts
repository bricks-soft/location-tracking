// Mock back office of the e2e kit (Unit 7). HTTP contract: docs/e2e/architecture.md §7.
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import type { Socket } from 'node:net';
import { EMULATOR_HOST, readEnv } from './env.ts';
import type { PremiseAuditEntry, RecordEvent, WireRecord } from './types.ts';
import { sortByRecordedAt, timeline } from './assertions.ts';
import { sleep, waitUntil, type WaitOptions } from './util.ts';

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
 * normally. Faulted requests (status/drop) store nothing. Faults never apply to the control endpoints (`/__*`).
 */
export interface Fault {
  path: string;
  status?: number;
  count?: number;
  delayMs?: number;
  drop?: boolean;
}

/** One `POST /logs` upload (`uploadLog()`), as a size summary. */
export interface StoredLogUpload {
  receivedAt: number;
  requestId: number;
  bytes: number;
  contentType: string;
}

export interface MockBackOfficeOptions {
  /** default E2E_BACKEND_PORT or 8787; 0 = any free port */
  port?: number;
  /** default '0.0.0.0' (the emulator reaches the host at 10.0.2.2) */
  host?: string;
  /** receives one line per request (also kept for Artifacts) */
  log?: (line: string) => void;
}

/** Keys of a wire record (docs/wire-format.md); other root keys of a bare `rootProperty: "."` body are params. */
const RECORD_KEYS = new Set([
  'uuid',
  'event',
  'timestamp',
  'recorded_at',
  'sent_at',
  'elapsed_realtime_ms',
  'boot_count',
  'is_moving',
  'odometer',
  'mock',
  'coords',
  'activity',
  'battery',
  'backend',
  'extras',
  'geofence',
  'provider',
  'reason',
  'heartbeat',
]);

function isRecord(value: unknown): value is WireRecord {
  return (
    typeof value === 'object' &&
    value !== null &&
    !Array.isArray(value) &&
    typeof (value as Record<string, unknown>)['uuid'] === 'string' &&
    typeof (value as Record<string, unknown>)['event'] === 'string'
  );
}

function isRecordArray(value: unknown): value is WireRecord[] {
  return Array.isArray(value) && value.length > 0 && value.every(isRecord);
}

/**
 * Splits one `POST /locations` body into records and params:
 * - an array (`rootProperty: "."` batch): every element that is a record; no params;
 * - an object that is itself a record (`rootProperty: "."` single): the record keys form the record, the other root
 *   keys are the params;
 * - any other object: every root key whose value is a record or a non-empty array of records holds records (in key
 *   order); the other root keys are the params.
 * A record is an object with string `uuid` and `event`.
 */
export function splitLocationsBody(body: unknown): { records: WireRecord[]; params: Record<string, unknown> } {
  if (Array.isArray(body)) return { records: body.filter(isRecord), params: {} };
  if (typeof body !== 'object' || body === null) return { records: [], params: {} };
  const root = body as Record<string, unknown>;
  if (isRecord(root)) {
    const record: Record<string, unknown> = {};
    const params: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(root)) (RECORD_KEYS.has(key) ? record : params)[key] = value;
    return { records: [record as unknown as WireRecord], params };
  }
  const records: WireRecord[] = [];
  const params: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(root)) {
    if (isRecord(value)) records.push(value);
    else if (isRecordArray(value)) records.push(...value);
    else params[key] = value;
  }
  return { records, params };
}

/** The records of one logged `/locations` request body (see [splitLocationsBody]); empty for other bodies. */
export function requestRecords(entry: RequestLogEntry): WireRecord[] {
  try {
    return splitLocationsBody(JSON.parse(entry.body)).records;
  } catch {
    return [];
  }
}

interface ActiveFault {
  path: string;
  status: number | undefined;
  remaining: number;
  delayMs: number;
  drop: boolean;
}

class HttpError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

function json(res: ServerResponse, status: number, body: unknown): void {
  const text = JSON.stringify(body);
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'content-length': Buffer.byteLength(text) });
  res.end(text);
}

function numberParam(params: URLSearchParams, name: string): number | undefined {
  const raw = params.get(name);
  if (raw === null || raw === '') return undefined;
  const value = Number(raw);
  if (!Number.isFinite(value)) throw new HttpError(400, `query parameter ${name} must be a number`);
  return value;
}

function boolParam(params: URLSearchParams, name: string): boolean {
  const raw = params.get(name);
  return raw === '1' || raw === 'true' || raw === 'yes';
}

/**
 * In-process mock back office (node:http). Endpoints:
 * - `POST /locations` (also PUT/PATCH and sub-paths `/locations/...`): plugin uploads; stores every record of single,
 *   batch and rootProperty bodies (see [splitLocationsBody]); answers `200 {"ok":true}`.
 * - `POST /premise-audit`: PremiseMonitor uploads (`{device_id?, entries: PremiseAuditEntry[]}`); answers
 *   `200 {"ok":true,"accepted":n}`.
 * - `POST /auth/refresh`: JWT refresh; answers `{accessToken: 'e2e-access-<n>', refreshToken: 'e2e-refresh-<n>',
 *   expires_in: 3600}` (n counts refreshes since the last reset).
 * - `POST /logs`: `uploadLog()` multipart bodies (stored as a size summary).
 * - Control (JSON): `GET /__records?event=&since=&unique=&uuid=`, `GET /__premise?since=&kind=&type=&event=`,
 *   `GET /__requests?path=&since=`, `POST /__faults` (a [Fault]), `DELETE /__faults`, `POST /__reset`, `GET /__health`.
 * The same server runs standalone for agents and curl: `npm run backoffice -- --port 8787` in testing/e2e-kit.
 */
export class MockBackOffice {
  readonly host: string;
  private configuredPort: number;
  private boundPort: number | undefined;
  private server: Server | undefined;
  private readonly sockets = new Set<Socket>();
  private readonly logSink: ((line: string) => void) | undefined;
  private readonly startedAt = Date.now();
  private requestSeq = 0;
  private storedRecords: StoredRecord[] = [];
  private storedPremise: StoredPremiseEntry[] = [];
  private requestLog: RequestLogEntry[] = [];
  private logLines: string[] = [];
  private logUploadList: StoredLogUpload[] = [];
  private faults: ActiveFault[] = [];
  private refreshCount = 0;

  constructor(options: MockBackOfficeOptions = {}) {
    this.host = options.host ?? '0.0.0.0';
    this.configuredPort = options.port ?? readEnv().backendPort;
    this.logSink = options.log;
  }

  /** The bound port (after start; the configured port before). */
  get port(): number {
    return this.boundPort ?? this.configuredPort;
  }

  get running(): boolean {
    return this.server?.listening === true;
  }

  /** Number of `/auth/refresh` requests answered since the last reset. */
  get refreshes(): number {
    return this.refreshCount;
  }

  /** Starts listening; resolves when bound. Rejects when the port is taken. Idempotent while running. */
  async start(): Promise<void> {
    if (this.running) return;
    const server = createServer((req, res) => {
      this.handle(req, res).catch((error: unknown) => {
        if (!res.headersSent) json(res, 500, { ok: false, error: error instanceof Error ? error.message : String(error) });
      });
    });
    server.on('connection', (socket: Socket) => {
      this.sockets.add(socket);
      socket.on('close', () => this.sockets.delete(socket));
    });
    await new Promise<void>((resolve, reject) => {
      const onError = (error: NodeJS.ErrnoException) => {
        server.off('listening', onListening);
        reject(
          error.code === 'EADDRINUSE'
            ? new Error(`mock back office: port ${this.configuredPort} on ${this.host} is in use (set E2E_BACKEND_PORT or stop the other server)`)
            : error,
        );
      };
      const onListening = () => {
        server.off('error', onError);
        resolve();
      };
      server.once('error', onError);
      server.once('listening', onListening);
      server.listen(this.configuredPort, this.host);
    });
    const address = server.address();
    this.boundPort = typeof address === 'object' && address !== null ? address.port : this.configuredPort;
    this.server = server;
  }

  /** Stops listening and closes open connections (keep-alive connections of the plugin included). */
  async stop(): Promise<void> {
    const server = this.server;
    if (!server) return;
    this.server = undefined;
    await new Promise<void>((resolve) => {
      server.close(() => resolve());
      for (const socket of this.sockets) socket.destroy();
      server.closeAllConnections();
    });
    this.sockets.clear();
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
  records(filter: RecordFilter = {}): StoredRecord[] {
    const events = filter.event === undefined ? undefined : new Set<string>(typeof filter.event === 'string' ? [filter.event] : filter.event);
    const seen = new Set<string>();
    const out: StoredRecord[] = [];
    for (const stored of this.storedRecords) {
      if (events && !events.has(stored.record.event)) continue;
      if (filter.since !== undefined && stored.receivedAt < filter.since) continue;
      if (filter.uuid !== undefined && stored.record.uuid !== filter.uuid) continue;
      if (filter.unique) {
        if (seen.has(stored.record.uuid)) continue;
        seen.add(stored.record.uuid);
      }
      out.push(stored);
    }
    return out;
  }

  /** Received PremiseMonitor entries in arrival order. */
  premiseRecords(filter: PremiseFilter = {}): StoredPremiseEntry[] {
    return this.storedPremise.filter((stored) => {
      const entry = stored.entry;
      if (filter.since !== undefined && stored.receivedAt < filter.since) return false;
      if (filter.kind !== undefined && entry.kind !== filter.kind) return false;
      if (filter.type !== undefined && entry.type !== filter.type) return false;
      if (filter.event !== undefined) {
        const name = entry.kind === 'record' ? entry.record?.event : entry.kind === 'event' ? entry.name : undefined;
        if (name !== filter.event) return false;
      }
      return true;
    });
  }

  requests(filter: { path?: string; since?: number } = {}): RequestLogEntry[] {
    return this.requestLog.filter(
      (entry) => (filter.path === undefined || entry.path === filter.path) && (filter.since === undefined || entry.receivedAt >= filter.since),
    );
  }

  /**
   * Waits until at least [count] records match [filter] (default 1) and resolves with them; the timeout error lists
   * every record received so far as a timeline.
   */
  async waitForRecords(filter: RecordFilter = {}, options: WaitOptions & { count?: number } = {}): Promise<StoredRecord[]> {
    const count = options.count ?? 1;
    try {
      return await this.waitFor((office) => {
        const found = office.records(filter);
        return found.length >= count ? found : undefined;
      }, { message: `${count} record(s) matching ${JSON.stringify(filter)}`, ...options });
    } catch (error) {
      const received = this.records().map((stored) => stored.record);
      throw new Error(`${(error as Error).message}\nrecords received (by recorded_at):\n${timeline(sortByRecordedAt(received)) || '  (none)'}`);
    }
  }

  /** `POST /logs` uploads since the last reset. */
  logUploads(): StoredLogUpload[] {
    return [...this.logUploadList];
  }

  /**
   * Polls [predicate] (default every 500 ms, timeout 30 s) until it returns a truthy value and resolves with it; `0`,
   * `''` and `NaN` count as "not yet" like `false`. Rejects after the timeout.
   */
  async waitFor<T>(predicate: (office: MockBackOffice) => T | undefined | null | false, options: WaitOptions = {}): Promise<T> {
    return waitUntil(
      () => {
        const value = predicate(this);
        return value ? value : undefined;
      },
      { message: 'the back office condition', ...options },
    );
  }

  setFault(fault: Fault): void {
    if (typeof fault.path !== 'string' || !fault.path.startsWith('/')) throw new Error(`fault path must start with '/', got ${String(fault.path)}`);
    if (fault.path.startsWith('/__')) throw new Error(`faults cannot apply to control endpoints (${fault.path})`);
    const count = fault.count ?? 1;
    if (!Number.isInteger(count) || (count < 1 && count !== -1)) throw new Error(`fault count must be >= 1 or -1, got ${String(fault.count)}`);
    this.faults.push({
      path: fault.path,
      status: fault.status,
      remaining: count,
      delayMs: Math.max(0, fault.delayMs ?? 0),
      drop: fault.drop === true,
    });
  }

  clearFaults(): void {
    this.faults = [];
  }

  /** Forgets records, premise entries, requests, log uploads, faults and issued tokens (the refresh counter). */
  reset(): void {
    this.storedRecords = [];
    this.storedPremise = [];
    this.requestLog = [];
    this.logLines = [];
    this.logUploadList = [];
    this.faults = [];
    this.refreshCount = 0;
  }

  /** The request log as text (for Artifacts): one line per request since the last reset. */
  logText(): string {
    return this.logLines.length > 0 ? `${this.logLines.join('\n')}\n` : '';
  }

  // ---- request handling

  private takeFault(path: string): ActiveFault | undefined {
    if (path.startsWith('/__')) return undefined;
    const index = this.faults.findIndex((f) => f.path === path && f.remaining !== 0);
    if (index < 0) return undefined;
    const fault = this.faults[index]!;
    if (fault.remaining > 0) fault.remaining -= 1;
    if (fault.remaining === 0) this.faults.splice(index, 1);
    return fault;
  }

  private async handle(req: IncomingMessage, res: ServerResponse): Promise<void> {
    const receivedAt = Date.now();
    const id = ++this.requestSeq;
    const url = new URL(req.url ?? '/', 'http://localhost');
    const method = (req.method ?? 'GET').toUpperCase();
    const chunks: Buffer[] = [];
    for await (const chunk of req) chunks.push(chunk as Buffer);
    const raw = Buffer.concat(chunks);
    const contentType = String(req.headers['content-type'] ?? '');
    const headers: Record<string, string> = {};
    for (const [name, value] of Object.entries(req.headers)) {
      if (value !== undefined) headers[name] = Array.isArray(value) ? value.join(', ') : value;
    }
    const entry: RequestLogEntry = {
      id,
      method,
      path: url.pathname,
      query: url.search.startsWith('?') ? url.search.slice(1) : url.search,
      receivedAt,
      status: 0,
      headers,
      body: contentType.startsWith('multipart/') ? `<multipart ${raw.length} bytes>` : raw.toString('utf8'),
    };
    this.requestLog.push(entry);
    let note = '';

    const fault = this.takeFault(url.pathname);
    if (fault) {
      if (fault.delayMs > 0) await sleep(fault.delayMs);
      if (fault.drop) {
        entry.fault = 'drop';
        entry.status = 0;
        this.writeLog(entry, 'fault: dropped');
        req.socket.destroy();
        return;
      }
      if (fault.status !== undefined || fault.delayMs === 0) {
        entry.fault = 'status';
        entry.status = fault.status ?? 500;
        json(res, entry.status, { ok: false, fault: true });
        this.writeLog(entry, `fault: status ${entry.status}`);
        return;
      }
      entry.fault = 'delay';
      note = `fault: delayed ${fault.delayMs} ms`;
    }

    try {
      const outcome = this.route(method, url, raw, contentType, entry);
      entry.status = outcome.status;
      json(res, outcome.status, outcome.body);
      this.writeLog(entry, [note, outcome.note].filter((part) => part).join('; '));
    } catch (error) {
      const status = error instanceof HttpError ? error.status : 500;
      entry.status = status;
      json(res, status, { ok: false, error: error instanceof Error ? error.message : String(error) });
      this.writeLog(entry, [note, error instanceof Error ? error.message : String(error)].filter((part) => part).join('; '));
    }
  }

  private route(
    method: string,
    url: URL,
    raw: Buffer,
    contentType: string,
    entry: RequestLogEntry,
  ): { status: number; body: unknown; note?: string } {
    const path = url.pathname;
    const params = url.searchParams;
    const parseBody = (): unknown => {
      const text = raw.toString('utf8');
      if (text.trim() === '') return undefined;
      try {
        return JSON.parse(text);
      } catch {
        throw new HttpError(400, 'invalid JSON body');
      }
    };

    if ((path === '/locations' || path.startsWith('/locations/')) && ['POST', 'PUT', 'PATCH'].includes(method)) {
      const { records, params: bodyParams } = splitLocationsBody(parseBody());
      const authorization = entry.headers['authorization'];
      records.forEach((record, index) => {
        this.storedRecords.push({
          receivedAt: entry.receivedAt,
          requestId: entry.id,
          path,
          index,
          batchSize: records.length,
          record,
          params: bodyParams,
          ...(authorization !== undefined ? { authorization } : {}),
        });
      });
      const events = records.map((r) => r.event).join(',');
      return { status: 200, body: { ok: true }, note: `${records.length} record(s)${events ? ` [${events}]` : ''}` };
    }
    if (path === '/premise-audit' && method === 'POST') {
      const body = parseBody() as { device_id?: unknown; entries?: unknown } | undefined;
      if (!body || typeof body !== 'object' || !Array.isArray(body.entries)) throw new HttpError(400, 'expected {device_id?, entries: [...]}');
      const deviceId = typeof body.device_id === 'string' ? body.device_id : undefined;
      for (const item of body.entries as PremiseAuditEntry[]) {
        this.storedPremise.push({ receivedAt: entry.receivedAt, requestId: entry.id, ...(deviceId !== undefined ? { deviceId } : {}), entry: item });
      }
      return { status: 200, body: { ok: true, accepted: body.entries.length }, note: `${body.entries.length} premise entr(y/ies)` };
    }
    if (path === '/auth/refresh' && method === 'POST') {
      const n = ++this.refreshCount;
      return {
        status: 200,
        body: { accessToken: `e2e-access-${n}`, refreshToken: `e2e-refresh-${n}`, expires_in: 3600 },
        note: `refresh #${n}`,
      };
    }
    if (path === '/logs' && method === 'POST') {
      this.logUploadList.push({ receivedAt: entry.receivedAt, requestId: entry.id, bytes: raw.length, contentType });
      return { status: 200, body: { ok: true, bytes: raw.length }, note: `log upload ${raw.length} bytes` };
    }
    if (path === '/__records' && method === 'GET') {
      const event = params.get('event');
      const since = numberParam(params, 'since');
      const uuid = params.get('uuid');
      return {
        status: 200,
        body: this.records({
          ...(event ? { event: event.split(',').map((e) => e.trim()).filter((e) => e) as RecordEvent[] } : {}),
          ...(since !== undefined ? { since } : {}),
          ...(uuid ? { uuid } : {}),
          unique: boolParam(params, 'unique'),
        }),
      };
    }
    if (path === '/__premise' && method === 'GET') {
      const since = numberParam(params, 'since');
      const kind = params.get('kind');
      const type = params.get('type');
      const event = params.get('event');
      return {
        status: 200,
        body: this.premiseRecords({
          ...(since !== undefined ? { since } : {}),
          ...(kind ? { kind: kind as PremiseAuditEntry['kind'] } : {}),
          ...(type ? { type } : {}),
          ...(event ? { event } : {}),
        }),
      };
    }
    if (path === '/__requests' && method === 'GET') {
      const since = numberParam(params, 'since');
      const filterPath = params.get('path');
      return { status: 200, body: this.requests({ ...(filterPath ? { path: filterPath } : {}), ...(since !== undefined ? { since } : {}) }) };
    }
    if (path === '/__faults' && method === 'POST') {
      const body = parseBody() as Fault | undefined;
      if (!body || typeof body !== 'object') throw new HttpError(400, 'expected a fault object {path, status?, count?, delayMs?, drop?}');
      try {
        this.setFault(body);
      } catch (error) {
        throw new HttpError(400, error instanceof Error ? error.message : String(error));
      }
      return { status: 200, body: { ok: true, faults: this.faults.length } };
    }
    if (path === '/__faults' && method === 'DELETE') {
      this.clearFaults();
      return { status: 200, body: { ok: true } };
    }
    if (path === '/__reset' && method === 'POST') {
      this.reset();
      // reset() also forgot this request's log entry; keep it so the log shows the reset.
      this.requestLog.push(entry);
      return { status: 200, body: { ok: true } };
    }
    if (path === '/__health' && method === 'GET') {
      return {
        status: 200,
        body: { ok: true, records: this.storedRecords.length, premise: this.storedPremise.length, uptimeMs: Date.now() - this.startedAt },
      };
    }
    throw new HttpError(404, `no endpoint ${method} ${path}`);
  }

  private writeLog(entry: RequestLogEntry, note: string): void {
    const auth = entry.headers['authorization'];
    const line =
      `${new Date(entry.receivedAt).toISOString()} #${entry.id} ${entry.method} ${entry.path}${entry.query ? `?${entry.query}` : ''}` +
      ` -> ${entry.status === 0 ? 'dropped' : entry.status}${note ? ` (${note})` : ''}${auth ? ` auth=${auth}` : ''}`;
    this.logLines.push(line);
    this.logSink?.(line);
  }
}
