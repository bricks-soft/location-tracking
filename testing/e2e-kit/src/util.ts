// Small shared helpers of the e2e kit (owned by Unit 7).

/** Thrown by functions that the kit does not implement (kept for suites that still reference it). */
export function notImplemented(what: string): never {
  throw new Error(`not implemented: ${what}`);
}

export interface WaitOptions {
  /** default 30000 */
  timeoutMs?: number;
  /** default 500 */
  intervalMs?: number;
  /** included in the timeout error */
  message?: string;
  signal?: AbortSignal;
}

/** Resolves after [ms]; rejects with the signal's reason when [signal] aborts first. */
export function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(signal.reason);
      return;
    }
    const onAbort = () => {
      clearTimeout(timer);
      reject(signal?.reason);
    };
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    signal?.addEventListener('abort', onAbort, { once: true });
  });
}

/** Error thrown by [waitUntil] when the timeout expires. [lastError] is the last error thrown by the probe. */
export class WaitTimeoutError extends Error {
  readonly lastError: unknown;

  constructor(message: string, lastError: unknown) {
    super(message);
    this.name = 'WaitTimeoutError';
    this.lastError = lastError;
  }
}

/**
 * Polls [probe] until it returns a value other than `undefined`, `null` or `false` and resolves with it; rejects with
 * an Error naming [WaitOptions.message] after the timeout. Errors thrown by [probe] are retried until the timeout; the
 * last one is included in the timeout error. The probe runs at least once, also with a timeout of 0.
 */
export async function waitUntil<T>(
  probe: () => T | undefined | null | false | Promise<T | undefined | null | false>,
  options: WaitOptions = {},
): Promise<T> {
  const timeoutMs = options.timeoutMs ?? 30_000;
  const intervalMs = options.intervalMs ?? 500;
  const deadline = Date.now() + timeoutMs;
  let lastError: unknown;
  for (;;) {
    if (options.signal?.aborted) throw options.signal.reason;
    try {
      const value = await probe();
      if (value !== undefined && value !== null && value !== false) return value;
      lastError = undefined;
    } catch (error) {
      lastError = error;
    }
    const remaining = deadline - Date.now();
    if (remaining <= 0) {
      const what = options.message ?? 'condition';
      const cause = lastError === undefined ? '' : `; last error: ${errorText(lastError)}`;
      throw new WaitTimeoutError(`timed out after ${timeoutMs} ms waiting for ${what}${cause}`, lastError);
    }
    await sleep(Math.min(intervalMs, remaining), options.signal);
  }
}

/** The message of an Error, or String(value). */
export function errorText(value: unknown): string {
  return value instanceof Error ? value.message : String(value);
}

/**
 * Quotes [value] for the device shell (`adb shell` passes one command line to `/system/bin/sh -c`): wraps it in single
 * quotes and escapes embedded single quotes. Values made only of safe characters are returned unchanged.
 */
export function shellQuote(value: string): string {
  if (value !== '' && /^[A-Za-z0-9_@%+=:,./-]+$/.test(value)) return value;
  return `'${value.replace(/'/g, `'\\''`)}'`;
}

/** Epoch ms of an ISO-8601 string or a number (already epoch ms). NaN for unparseable input. */
export function toEpochMs(value: string | number): number {
  return typeof value === 'number' ? value : Date.parse(value);
}
