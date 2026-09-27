// Owned by Unit 1 (TS wrapper + web).
import { CapacitorException } from '@capacitor/core';
import type { ExceptionCode } from '@capacitor/core';

import type { ErrorCode } from '../definitions';

/** The `{ code, message }` shape passed to a watch callback on error. */
export interface WebErrorInfo {
  code: ErrorCode;
  message: string;
}

/** A rejection shaped like the native bridge's: a `CapacitorException` whose `code` is an `ErrorCode`. */
export function webError(code: ErrorCode, message: string): CapacitorException {
  return new CapacitorException(message, code as unknown as ExceptionCode);
}

/** `GeolocationPositionError.code`: 1 = PERMISSION_DENIED, 2 = POSITION_UNAVAILABLE, 3 = TIMEOUT. */
export function positionErrorInfo(error: { code?: number; message?: string } | null | undefined): WebErrorInfo {
  const raw = error && typeof error.code === 'number' ? error.code : 0;
  const code: ErrorCode = raw === 1 ? 'PERMISSION_DENIED' : raw === 3 ? 'TIMEOUT' : 'UNAVAILABLE';
  const fallback =
    code === 'PERMISSION_DENIED'
      ? 'Location permission denied.'
      : code === 'TIMEOUT'
        ? 'Timed out waiting for a location.'
        : 'Location unavailable.';
  const message = error && typeof error.message === 'string' && error.message ? error.message : fallback;
  return { code, message };
}
