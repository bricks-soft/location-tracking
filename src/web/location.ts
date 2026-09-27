// Owned by Unit 1 (TS wrapper + web).
import type { Location, RecordEvent } from '../definitions';

/** A version 4 UUID: `crypto.randomUUID()`, else built from `crypto.getRandomValues()`, else from `Math.random()`. */
export function uuid(): string {
  const webCrypto: Crypto | undefined = typeof crypto !== 'undefined' ? crypto : undefined;
  if (webCrypto && typeof webCrypto.randomUUID === 'function') {
    return webCrypto.randomUUID();
  }
  const bytes = new Uint8Array(16);
  if (webCrypto && typeof webCrypto.getRandomValues === 'function') {
    webCrypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i++) {
      bytes[i] = Math.floor(Math.random() * 256);
    }
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  let hex = '';
  for (let i = 0; i < bytes.length; i++) {
    hex += (bytes[i] + 0x100).toString(16).slice(1);
  }
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** ISO-8601 UTC with milliseconds (`yyyy-MM-ddTHH:mm:ss.SSSZ`). Invalid input falls back to now. */
export function isoTime(epochMs: number): string {
  const ms = typeof epochMs === 'number' && isFinite(epochMs) ? Math.round(epochMs) : Date.now();
  return new Date(ms).toISOString();
}

/** Monotonic milliseconds since the page's time origin: the closest web equivalent of `elapsedRealtime`. */
function elapsedMs(): number {
  const hasClock = typeof performance !== 'undefined' && typeof performance.now === 'function';
  return hasClock ? Math.round(performance.now()) : 0;
}

function numberOrNull(value: number | null | undefined): number | null {
  return typeof value === 'number' && isFinite(value) ? value : null;
}

/** Maps a browser fix to the plugin's `Location` record shape. `extras` is left out when empty. */
export function toLocation(
  position: GeolocationPosition,
  event: RecordEvent,
  extras?: Record<string, unknown>,
): Location {
  const coords = position.coords;
  const location: Location = {
    uuid: uuid(),
    event,
    timestamp: isoTime(position.timestamp),
    recorded_at: isoTime(Date.now()),
    elapsed_realtime_ms: elapsedMs(),
    boot_count: -1,
    is_moving: false,
    odometer: 0,
    mock: false,
    coords: {
      latitude: coords.latitude,
      longitude: coords.longitude,
      accuracy: coords.accuracy,
      altitude: numberOrNull(coords.altitude),
      altitude_accuracy: numberOrNull(coords.altitudeAccuracy),
      speed: numberOrNull(coords.speed),
      speed_accuracy: null,
      heading: numberOrNull(coords.heading),
      heading_accuracy: null,
    },
    activity: { type: 'unknown', confidence: 0 },
    battery: { level: -1, is_charging: false },
    backend: 'web',
  };
  if (extras && Object.keys(extras).length > 0) {
    location.extras = extras;
  }
  return location;
}
