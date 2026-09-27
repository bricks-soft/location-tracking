// Assertion helpers over received records (Unit 7); all throw AssertionError with a readable timeline of the records
// involved. Pure functions: no device, no I/O.
import { AssertionError } from 'node:assert';
import type { LatLon, RecordEvent, WireRecord } from './types.ts';
import { toEpochMs } from './util.ts';

/** Mean Earth radius used by every geometry helper (IUGG), meters. */
export const EARTH_RADIUS_M = 6_371_008.8;

/** Minimum spacing of heartbeats in deep Doze without the battery exemption (allow-while-idle alarms), seconds. */
export const IDLE_PACED_INTERVAL_S = 9 * 60;

/** Matches one record: an event name, a partial shape, or a predicate. */
export type RecordMatcher =
  | RecordEvent
  | { event: RecordEvent; reason?: string; is_moving?: boolean; identifier?: string; action?: string }
  | ((record: WireRecord) => boolean);

function fail(message: string): never {
  throw new AssertionError({ message });
}

function epoch(record: WireRecord): number {
  return Date.parse(record.recorded_at);
}

/** Records ordered by `recorded_at`, then by their position in [records] (stable). */
export function sortByRecordedAt(records: readonly WireRecord[]): WireRecord[] {
  return records
    .map((record, index) => ({ record, index, at: epoch(record) }))
    .sort((a, b) => (a.at === b.at || Number.isNaN(a.at - b.at) ? a.index - b.index : a.at - b.at))
    .map((entry) => entry.record);
}

/** One line per record: `recorded_at event[:reason] [moving] [geofence id action] [heartbeat strategy] uuid`. */
export function describeRecord(record: WireRecord): string {
  const parts = [record.recorded_at, record.event];
  if (record.reason) parts[1] += `:${record.reason}`;
  if (record.is_moving) parts.push('moving');
  if (record.geofence) parts.push(`${record.geofence.identifier} ${record.geofence.action}`);
  if (record.heartbeat) parts.push(`hb=${record.heartbeat.strategy}${record.heartbeat.device_idle ? ',idle' : ''}`);
  parts.push(`boot=${record.boot_count}`);
  parts.push(record.uuid.slice(0, 8));
  return parts.join(' ');
}

/** A timeline of [records] (at most [max] lines, the last ones), for assertion messages. */
export function timeline(records: readonly WireRecord[], max = 60): string {
  const lines = records.map((record, index) => `  ${String(index).padStart(3)} ${describeRecord(record)}`);
  if (lines.length <= max) return lines.join('\n');
  return [`  ... ${lines.length - max} earlier record(s)`, ...lines.slice(-max)].join('\n');
}

function describeMatcher(matcher: RecordMatcher): string {
  if (typeof matcher === 'string') return matcher;
  if (typeof matcher === 'function') return matcher.name ? `predicate ${matcher.name}` : 'predicate';
  return JSON.stringify(matcher);
}

/** True if [record] matches [matcher]. */
export function matches(record: WireRecord, matcher: RecordMatcher): boolean {
  if (typeof matcher === 'string') return record.event === matcher;
  if (typeof matcher === 'function') return matcher(record);
  if (record.event !== matcher.event) return false;
  if (matcher.reason !== undefined && record.reason !== matcher.reason) return false;
  if (matcher.is_moving !== undefined && record.is_moving !== matcher.is_moving) return false;
  if (matcher.identifier !== undefined && record.geofence?.identifier !== matcher.identifier) return false;
  if (matcher.action !== undefined && record.geofence?.action !== matcher.action) return false;
  return true;
}

/**
 * Asserts that records matching [expected] occur in this order in [records] (as a subsequence; other records may be
 * interleaved). Records are ordered by `recorded_at`, then by their position in [records]. Returns the matched
 * records.
 */
export function inOrder(records: readonly WireRecord[], expected: readonly RecordMatcher[]): WireRecord[] {
  const sorted = sortByRecordedAt(records);
  const matched: WireRecord[] = [];
  let position = 0;
  for (let i = 0; i < expected.length; i++) {
    const matcher = expected[i]!;
    let found = -1;
    for (let j = position; j < sorted.length; j++) {
      if (matches(sorted[j]!, matcher)) {
        found = j;
        break;
      }
    }
    if (found < 0) {
      const done = expected.slice(0, i).map(describeMatcher).join(', ') || '(none)';
      fail(
        `inOrder: no record matching ${describeMatcher(matcher)} (expectation ${i + 1} of ${expected.length}) ` +
          `after record ${position - 1}; matched so far: ${done}\nrecords by recorded_at:\n${timeline(sorted)}`,
      );
    }
    matched.push(sorted[found]!);
    position = found + 1;
  }
  return matched;
}

/** Asserts that no uuid occurs twice. */
export function noDuplicates(records: readonly WireRecord[]): void {
  const counts = new Map<string, WireRecord[]>();
  for (const record of records) {
    const list = counts.get(record.uuid) ?? [];
    list.push(record);
    counts.set(record.uuid, list);
  }
  const duplicates = [...counts.values()].filter((list) => list.length > 1);
  if (duplicates.length > 0) {
    const lines = duplicates.map((list) => `  ${list.length}x ${describeRecord(list[0]!)} (uuid ${list[0]!.uuid})`);
    fail(`noDuplicates: ${duplicates.length} uuid(s) occur more than once:\n${lines.join('\n')}`);
  }
}

export interface CadenceOptions {
  /** heartbeat.minInterval, seconds */
  minIntervalS: number;
  /** heartbeat.maxInterval, seconds */
  maxIntervalS: number;
  /** slack for alarm and scheduling latency, seconds (default 30) */
  toleranceS?: number;
  /** deep Doze without the exemption: gaps may be up to 9 min + tolerance and must be >= 9 min - tolerance */
  idlePaced?: boolean;
  /** only records with recorded_at >= since (ISO or epoch ms) */
  since?: string | number;
}

/**
 * Asserts the heartbeat window: each heartbeat is created between min and max (+ tolerance) seconds after the previous
 * record of the same boot (any event restarts the window), and while tracking is on no gap between consecutive records
 * exceeds max + tolerance. Returns the observed heartbeat gaps in seconds.
 *
 * Details:
 * - Bounds: `[minIntervalS - tol, maxIntervalS + tol]`; with `idlePaced`: `[540 - tol, max(540, maxIntervalS) + tol]`.
 * - Tracking is on from the start of [records] and after every `tracking_start`, off after a `tracking_stop`.
 * - A heartbeat whose previous record has another `boot_count` is not measured (the window restarts at boot).
 * - The "no longer gap" rule skips pairs where tracking was off, the later record is a `tracking_start`, or the boot
 *   changed.
 */
export function heartbeatCadence(records: readonly WireRecord[], options: CadenceOptions): number[] {
  const tol = options.toleranceS ?? 30;
  const lower = options.idlePaced ? IDLE_PACED_INTERVAL_S - tol : options.minIntervalS - tol;
  const upper = (options.idlePaced ? Math.max(IDLE_PACED_INTERVAL_S, options.maxIntervalS) : options.maxIntervalS) + tol;
  const since = options.since === undefined ? undefined : toEpochMs(options.since);
  const sorted = sortByRecordedAt(records).filter((record) => since === undefined || epoch(record) >= since);
  const gaps: number[] = [];
  const problems: string[] = [];
  let on = true;
  for (let i = 1; i < sorted.length; i++) {
    const prev = sorted[i - 1]!;
    const record = sorted[i]!;
    if (prev.event === 'tracking_stop') on = false;
    if (prev.event === 'tracking_start') on = true;
    const gap = (epoch(record) - epoch(prev)) / 1000;
    const sameBoot = prev.boot_count === record.boot_count;
    if (record.event === 'heartbeat' && sameBoot) {
      gaps.push(gap);
      if (gap < lower || gap > upper) {
        problems.push(`heartbeat ${i} came ${gap.toFixed(1)} s after record ${i - 1} (allowed ${lower}..${upper} s)`);
      }
    } else if (on && sameBoot && record.event !== 'tracking_start' && gap > upper) {
      problems.push(`gap of ${gap.toFixed(1)} s between records ${i - 1} and ${i} while tracking was on (max ${upper} s)`);
    }
  }
  if (problems.length > 0) {
    fail(`heartbeatCadence: ${problems.length} violation(s):\n  ${problems.join('\n  ')}\nrecords by recorded_at:\n${timeline(sorted)}`);
  }
  return gaps;
}

export interface Gap {
  from: WireRecord;
  to: WireRecord;
  seconds: number;
  /** why the gap is acceptable */
  explanation: string;
}

const RESUME_REASONS = new Set(['restore', 'boot', 'package_replaced']);
const LATE_STOP_REASONS = new Set(['reboot', 'package_replaced', 'service_start_failed', 'permission_denied']);

/**
 * Asserts that every gap longer than [maxGapS] between consecutive records (by `recorded_at`) is explained. A gap is
 * explained when:
 * - tracking was off: the earlier record is a `tracking_stop`, or a `tracking_stop` came before it without a
 *   `tracking_start` since;
 * - it ends with a `tracking_start` whose reason is `restore`, `boot` or `package_replaced` (the process or the phone
 *   was down), or with a `tracking_stop` whose reason is `reboot`, `package_replaced`, `service_start_failed` or
 *   `permission_denied` (recorded after the restart);
 * - it ends with a `providerchange`;
 * - idle pacing: either record carries heartbeat metadata with strategy `idle_paced` (or `device_idle: true`) and the
 *   gap is at most 540 s + [maxGapS].
 * Returns the explained gaps.
 */
export function gapsExplained(records: readonly WireRecord[], options: { maxGapS: number }): Gap[] {
  const sorted = sortByRecordedAt(records);
  const explained: Gap[] = [];
  const problems: string[] = [];
  let on = true;
  for (let i = 1; i < sorted.length; i++) {
    const from = sorted[i - 1]!;
    const to = sorted[i]!;
    if (from.event === 'tracking_stop') on = false;
    if (from.event === 'tracking_start') on = true;
    const seconds = (epoch(to) - epoch(from)) / 1000;
    if (seconds <= options.maxGapS) continue;
    let explanation: string | undefined;
    if (!on) explanation = 'tracking was off';
    else if (to.event === 'tracking_start' && to.reason !== undefined && RESUME_REASONS.has(to.reason)) explanation = `tracking_start:${to.reason}`;
    else if (to.event === 'tracking_stop' && to.reason !== undefined && LATE_STOP_REASONS.has(to.reason)) explanation = `tracking_stop:${to.reason}`;
    else if (to.event === 'providerchange') explanation = 'providerchange';
    else if (
      (from.heartbeat?.strategy === 'idle_paced' ||
        to.heartbeat?.strategy === 'idle_paced' ||
        from.heartbeat?.device_idle === true ||
        to.heartbeat?.device_idle === true) &&
      seconds <= IDLE_PACED_INTERVAL_S + options.maxGapS
    ) {
      explanation = 'idle pacing';
    }
    if (explanation) explained.push({ from, to, seconds, explanation });
    else problems.push(`unexplained gap of ${seconds.toFixed(1)} s between records ${i - 1} and ${i} (max ${options.maxGapS} s)`);
  }
  if (problems.length > 0) fail(`gapsExplained: ${problems.join('; ')}\nrecords by recorded_at:\n${timeline(sorted)}`);
  return explained;
}

/** Asserts `from <= value <= to` (ISO strings or epoch ms). */
export function withinWindow(
  value: string | number,
  window: { from: string | number; to: string | number },
  label?: string,
): void {
  const v = toEpochMs(value);
  const from = toEpochMs(window.from);
  const to = toEpochMs(window.to);
  const name = label ?? 'value';
  if (Number.isNaN(v) || Number.isNaN(from) || Number.isNaN(to)) {
    fail(`withinWindow: ${name} ${String(value)} or the window ${String(window.from)}..${String(window.to)} is not a time`);
  }
  if (v < from || v > to) {
    const iso = (ms: number) => new Date(ms).toISOString();
    fail(`withinWindow: ${name} ${iso(v)} is outside ${iso(from)} .. ${iso(to)} (${v < from ? from - v : v - to} ms outside)`);
  }
}

/** Asserts `|actual - expected| <= tolerance` where tolerance is absolute or `{ pct }` of expected. */
export function approximately(actual: number, expected: number, tolerance: number | { pct: number }, label?: string): void {
  const allowed = typeof tolerance === 'number' ? tolerance : (Math.abs(expected) * tolerance.pct) / 100;
  const diff = Math.abs(actual - expected);
  if (!(diff <= allowed)) {
    const name = label ?? 'value';
    const tol = typeof tolerance === 'number' ? `±${tolerance}` : `±${tolerance.pct}% (±${allowed.toFixed(3)})`;
    fail(`approximately: ${name} is ${actual}, expected ${expected} ${tol} (off by ${diff.toFixed(3)})`);
  }
}

// ---- geometry and travel summaries (pure)

const RAD = Math.PI / 180;

/** Great-circle distance on a sphere of [EARTH_RADIUS_M] (haversine formula), meters. */
export function haversineMeters(a: LatLon, b: LatLon): number {
  const dLat = (b.lat - a.lat) * RAD;
  const dLon = (b.lon - a.lon) * RAD;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * RAD) * Math.cos(b.lat * RAD) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** Sum of the segment lengths of [points], meters. */
export function routeLengthMeters(points: readonly LatLon[]): number {
  let total = 0;
  for (let i = 1; i < points.length; i++) total += haversineMeters(points[i - 1]!, points[i]!);
  return total;
}

/** A point [north]/[east] meters from [origin] (local flat approximation; accurate to < 0.1% within a few km). */
export function offsetMeters(origin: LatLon, north: number, east: number): LatLon {
  const lat = origin.lat + (north / EARTH_RADIUS_M) / RAD;
  const lon = origin.lon + (east / (EARTH_RADIUS_M * Math.cos(origin.lat * RAD))) / RAD;
  return origin.alt === undefined ? { lat, lon } : { lat, lon, alt: origin.alt };
}

export interface TravelSummary {
  /** increase of `odometer` over the records, meters (see [travelSummary]) */
  odometerM: number;
  /** sum of the distances between consecutive moving fixes, meters (see [travelSummary]) */
  pathM: number;
  /** sum of the distances between ALL consecutive fixes of a chain, moving or not, meters (see [travelSummary]) */
  allFixesPathM: number;
  /** time between motionchange(true) and the next motionchange(false) (or tracking_stop, or the last record), summed, seconds */
  movingS: number;
  /** sum of the time between consecutive moving fixes, seconds: the travel time (see [travelSummary]) */
  travelS: number;
  /** first to last record, seconds */
  spanS: number;
  /** number of fix records used (location and motionchange records with coords) */
  fixes: number;
  /** number of segments counted in pathM / travelS */
  segments: number;
}

const FIX_EVENTS = new Set<string>(['location', 'motionchange']);

function fixTime(record: WireRecord): number {
  const t = record.timestamp !== null ? Date.parse(record.timestamp) : Number.NaN;
  return Number.isNaN(t) ? epoch(record) : t;
}

/**
 * What the back office computes from a day's records: distance, travel time, span. Records are ordered by
 * `recorded_at` first.
 *
 * Formulas:
 * - fixes = records with event `location` or `motionchange` that have `coords`. A `tracking_start`, a `tracking_stop`
 *   or a change of `boot_count` between two fixes breaks the chain (no segment across it).
 * - moving segment = two consecutive fixes in one chain that both have `is_moving: true` (the `motionchange` that
 *   starts moving has `is_moving: true`; the one that ends it has `false` and ends the moving period).
 * - `pathM` = Σ haversine(a.coords, b.coords) over moving segments.
 * - `allFixesPathM` = the same sum over every pair of consecutive fixes in a chain, also when not moving (the sum the
 *   §8 stub described; it includes GPS jitter around stops, so it is at least `pathM`).
 * - `travelS` = Σ (fixTime(b) − fixTime(a)) over moving segments, where fixTime = `timestamp` (the fix time), else
 *   `recorded_at`; negative differences count as 0. This is the travel time: the stop timeout after the last moving
 *   fix is not included.
 * - `movingS` = Σ over moving periods of (end − start) by `recorded_at`, where a period starts at a
 *   `motionchange` with `is_moving: true` and ends at the next `motionchange` with `is_moving: false`, a
 *   `tracking_stop`, or the last record. It includes the stop timeout, so it is an upper bound of the travel time.
 * - `odometerM` = Σ max(0, b.odometer − a.odometer) over consecutive records (an odometer reset adds 0); equal to
 *   last − first when the odometer was not reset.
 * - `spanS` = last `recorded_at` − first `recorded_at`.
 */
export function travelSummary(records: readonly WireRecord[]): TravelSummary {
  const sorted = sortByRecordedAt(records);
  let odometerM = 0;
  let pathM = 0;
  let allFixesPathM = 0;
  let travelS = 0;
  let movingS = 0;
  let fixes = 0;
  let segments = 0;
  let previousFix: WireRecord | undefined;
  let movingSince: number | undefined;
  for (let i = 0; i < sorted.length; i++) {
    const record = sorted[i]!;
    if (i > 0) {
      const prev = sorted[i - 1]!;
      const delta = record.odometer - prev.odometer;
      if (Number.isFinite(delta) && delta > 0) odometerM += delta;
    }
    if (record.event === 'tracking_start' || record.event === 'tracking_stop') previousFix = undefined;
    if (previousFix && previousFix.boot_count !== record.boot_count) previousFix = undefined;
    if (FIX_EVENTS.has(record.event) && record.coords) {
      fixes += 1;
      const a = previousFix?.coords;
      const b = record.coords;
      const distance = a ? haversineMeters({ lat: a.latitude, lon: a.longitude }, { lat: b.latitude, lon: b.longitude }) : 0;
      allFixesPathM += distance;
      if (previousFix && previousFix.is_moving && record.is_moving) {
        pathM += distance;
        travelS += Math.max(0, fixTime(record) - fixTime(previousFix)) / 1000;
        segments += 1;
      }
      previousFix = record;
    }
    if (record.event === 'motionchange' && record.is_moving && movingSince === undefined) movingSince = epoch(record);
    else if (
      movingSince !== undefined &&
      ((record.event === 'motionchange' && !record.is_moving) || record.event === 'tracking_stop')
    ) {
      movingS += Math.max(0, epoch(record) - movingSince) / 1000;
      movingSince = undefined;
    }
  }
  const first = sorted[0];
  const last = sorted[sorted.length - 1];
  if (movingSince !== undefined && last) movingS += Math.max(0, epoch(last) - movingSince) / 1000;
  const spanS = first && last ? (epoch(last) - epoch(first)) / 1000 : 0;
  return { odometerM, pathM, allFixesPathM, movingS, travelS, spanS, fixes, segments };
}

export interface OnlineStatus {
  /** tracking was on at the time and the last record before it is at most maxSilenceS old */
  online: boolean;
  /** tracking state from the last tracking_start / tracking_stop at or before the time; 'unknown' without either */
  state: 'on' | 'off' | 'unknown';
  /** the last record at or before the time */
  lastRecord: WireRecord | undefined;
  /** seconds between lastRecord.recorded_at and the time (Infinity without a record) */
  silenceS: number;
}

/**
 * What the back office shows as "tracking online" at [at] (ISO or epoch ms): the state is ON after a `tracking_start`
 * and OFF after a `tracking_stop` (records at or before [at], ordered by `recorded_at`); when neither exists, records
 * other than `current_position` / `watch_position` prove tracking was on. Online = state ON and the last record is at
 * most [maxSilenceS] old (use heartbeat.maxInterval + grace, e.g. 120 + 60).
 */
export function onlineStatus(records: readonly WireRecord[], at: string | number, maxSilenceS: number): OnlineStatus {
  const atMs = toEpochMs(at);
  const before = sortByRecordedAt(records).filter((record) => epoch(record) <= atMs);
  let state: OnlineStatus['state'] = 'unknown';
  for (const record of before) {
    if (record.event === 'tracking_start') state = 'on';
    else if (record.event === 'tracking_stop') state = 'off';
    else if (state === 'unknown' && record.event !== 'current_position' && record.event !== 'watch_position') state = 'on';
  }
  const lastRecord = before[before.length - 1];
  const silenceS = lastRecord ? (atMs - epoch(lastRecord)) / 1000 : Number.POSITIVE_INFINITY;
  return { online: state === 'on' && silenceS <= maxSilenceS, state, lastRecord, silenceS };
}
