// Owned by Unit 1 (TS wrapper + web).
import type { CapacitorException } from '@capacitor/core';
import type { PermissionState } from '@capacitor/core';

import { positionErrorInfo, webError } from './errors';

/** Largest delay `setTimeout` accepts; longer delays would fire immediately. */
const MAX_TIMER_MS = 2147483647;

/** How long the permission probe waits for a fix once the user has answered the prompt. */
const PROBE_TIMEOUT_MS = 10000;

/** Overall limit for the permission probe: some browsers never call back when their prompt is dismissed. */
const PROBE_DEADLINE_MS = 60000;

export interface SampleOptions {
  /** Number of fixes to take, at least 1. */
  samples: number;
  /** Overall deadline for all samples, ms. */
  timeout: number;
  /** Passed to the first request only, so the browser may answer it from its cache; later samples are fresh. */
  maximumAge: number;
  enableHighAccuracy: boolean;
}

/** Returns `navigator.geolocation`, or `undefined` when the browser has none. */
export function browserGeolocation(): Geolocation | undefined {
  return typeof navigator !== 'undefined' && navigator && navigator.geolocation ? navigator.geolocation : undefined;
}

/**
 * Takes up to `samples` fixes with consecutive `getCurrentPosition` calls and resolves the most accurate one.
 * The whole run shares one deadline (`timeout`). When the deadline passes, or a later call fails, it resolves the
 * best fix taken so far; with no fix it rejects with `TIMEOUT`, `PERMISSION_DENIED` or `UNAVAILABLE`.
 */
export function sampleBestPosition(geolocation: Geolocation, options: SampleOptions): Promise<GeolocationPosition> {
  return new Promise<GeolocationPosition>((resolve, reject) => {
    const deadline = Date.now() + options.timeout;
    let best: GeolocationPosition | null = null;
    let taken = 0;
    let done = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    const finish = (error?: CapacitorException): void => {
      if (done) {
        return;
      }
      done = true;
      if (timer !== undefined) {
        clearTimeout(timer);
      }
      if (best) {
        resolve(best);
      } else {
        reject(error || webError('TIMEOUT', `No location within ${options.timeout} ms.`));
      }
    };

    const next = (): void => {
      if (done) {
        return;
      }
      try {
        geolocation.getCurrentPosition(
          (position) => {
            if (done) {
              return;
            }
            taken++;
            if (!best || position.coords.accuracy <= best.coords.accuracy) {
              best = position;
            }
            if (taken >= options.samples) {
              finish();
            } else {
              next();
            }
          },
          (error) => {
            const info = positionErrorInfo(error);
            finish(webError(info.code, info.message));
          },
          {
            enableHighAccuracy: options.enableHighAccuracy,
            maximumAge: taken === 0 ? options.maximumAge : 0,
            timeout: Math.max(0, deadline - Date.now()),
          },
        );
      } catch (e) {
        finish(webError('UNAVAILABLE', e instanceof Error ? e.message : String(e)));
      }
    };

    // The browser's own timeout does not run while its permission prompt is open; this deadline does.
    if (options.timeout <= MAX_TIMER_MS) {
      timer = setTimeout(() => finish(), options.timeout);
    }
    next();
  });
}

/**
 * Asks for one fix so the browser shows its permission prompt, and waits for the user's answer.
 * Resolves `'denied'` on PERMISSION_DENIED and `'granted'` otherwise: the browser reports TIMEOUT and
 * POSITION_UNAVAILABLE only after permission was granted. Resolves `'prompt'` if nothing happens within a minute.
 */
export function probeLocationPermission(geolocation: Geolocation): Promise<PermissionState> {
  return new Promise<PermissionState>((resolve) => {
    let done = false;
    const finish = (state: PermissionState): void => {
      if (!done) {
        done = true;
        clearTimeout(timer);
        resolve(state);
      }
    };
    const timer = setTimeout(() => finish('prompt'), PROBE_DEADLINE_MS);
    try {
      geolocation.getCurrentPosition(
        () => finish('granted'),
        (error) => finish(positionErrorInfo(error).code === 'PERMISSION_DENIED' ? 'denied' : 'granted'),
        { enableHighAccuracy: false, maximumAge: Infinity, timeout: PROBE_TIMEOUT_MS },
      );
    } catch {
      finish('prompt');
    }
  });
}

/** State of the browser's `geolocation` permission, or `undefined` when the Permissions API cannot tell. */
export async function queryLocationPermission(): Promise<PermissionState | undefined> {
  const permissions = typeof navigator !== 'undefined' && navigator ? navigator.permissions : undefined;
  if (!permissions || typeof permissions.query !== 'function') {
    return undefined;
  }
  try {
    const status = await permissions.query({ name: 'geolocation' });
    const state = status.state;
    return state === 'granted' || state === 'denied' || state === 'prompt' ? state : 'prompt';
  } catch {
    return undefined;
  }
}
