// STUB — owned by Unit 7 (e2e-kit).

/** Thrown by every scaffold stub. */
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

/** Resolves after [ms]. */
export function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, ms);
    signal?.addEventListener('abort', () => {
      clearTimeout(timer);
      reject(signal.reason);
    });
  });
}

/**
 * Polls [probe] until it returns a value other than `undefined`, `null` or `false` and resolves with it; rejects with
 * an Error naming [WaitOptions.message] after the timeout. Errors thrown by [probe] are retried until the timeout.
 */
export async function waitUntil<T>(
  probe: () => T | undefined | null | false | Promise<T | undefined | null | false>,
  options?: WaitOptions,
): Promise<T> {
  return notImplemented('waitUntil');
}
