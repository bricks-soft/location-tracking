// STUB — owned by Unit 7 (e2e-kit). Assertion helpers over received records; all throw AssertionError-style errors
// with a readable timeline of the records involved.
import type { LatLon, RecordEvent, WireRecord } from './types.ts';
import { notImplemented } from './util.ts';

/** Matches one record: an event name, a partial shape, or a predicate. */
export type RecordMatcher =
  | RecordEvent
  | { event: RecordEvent; reason?: string; is_moving?: boolean; identifier?: string; action?: string }
  | ((record: WireRecord) => boolean);

/**
 * Asserts that records matching [expected] occur in this order in [records] (as a subsequence; other records may be
 * interleaved). Records are ordered by `recorded_at`, then by their position in [records]. Returns the matched
 * records.
 */
export function inOrder(records: readonly WireRecord[], expected: readonly RecordMatcher[]): WireRecord[] {
  return notImplemented('assertions.inOrder');
}

/** Asserts that no uuid occurs twice. */
export function noDuplicates(records: readonly WireRecord[]): void {
  return notImplemented('assertions.noDuplicates');
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
 */
export function heartbeatCadence(records: readonly WireRecord[], options: CadenceOptions): number[] {
  return notImplemented('assertions.heartbeatCadence');
}

export interface Gap {
  from: WireRecord;
  to: WireRecord;
  seconds: number;
}

/**
 * Asserts that every gap longer than [maxGapS] between consecutive records is explained: tracking was stopped before
 * it (`tracking_stop`), or it ends with a `tracking_start` (`restore` | `boot` | `package_replaced`) or a
 * `providerchange`. Returns the explained gaps.
 */
export function gapsExplained(records: readonly WireRecord[], options: { maxGapS: number }): Gap[] {
  return notImplemented('assertions.gapsExplained');
}

/** Asserts `from <= value <= to` (ISO strings or epoch ms). */
export function withinWindow(
  value: string | number,
  window: { from: string | number; to: string | number },
  label?: string,
): void {
  return notImplemented('assertions.withinWindow');
}

/** Asserts `|actual - expected| <= tolerance` where tolerance is absolute or `{ pct }` of expected. */
export function approximately(actual: number, expected: number, tolerance: number | { pct: number }, label?: string): void {
  return notImplemented('assertions.approximately');
}

// ---- geometry and travel summaries (pure)

/** Great-circle distance, meters. */
export function haversineMeters(a: LatLon, b: LatLon): number {
  return notImplemented('assertions.haversineMeters');
}

/** Sum of the segment lengths of [points], meters. */
export function routeLengthMeters(points: readonly LatLon[]): number {
  return notImplemented('assertions.routeLengthMeters');
}

/** A point [north]/[east] meters from [origin]. */
export function offsetMeters(origin: LatLon, north: number, east: number): LatLon {
  return notImplemented('assertions.offsetMeters');
}

export interface TravelSummary {
  /** last odometer - first odometer, meters */
  odometerM: number;
  /** sum of the distances between consecutive location/motionchange coords, meters */
  pathM: number;
  /** time between motionchange(true) and the next motionchange(false) (or the last record), summed, seconds */
  movingS: number;
  /** first to last record, seconds */
  spanS: number;
}

/** What the back office computes from a day's records: distance, moving time, span. */
export function travelSummary(records: readonly WireRecord[]): TravelSummary {
  return notImplemented('assertions.travelSummary');
}
