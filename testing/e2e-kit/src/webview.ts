// STUB — owned by Unit 7 (e2e-kit). Signatures are the contract of docs/e2e/architecture.md §8.
import type { Adb } from './adb.ts';
import { notImplemented } from './util.ts';

/** A plugin promise rejection surfaced through [WebViewDriver.callPlugin]; [code] is the plugin's `ErrorCode`. */
export class PluginCallError extends Error {
  readonly code: string | undefined;

  constructor(message: string, code: string | undefined) {
    super(message);
    this.name = 'PluginCallError';
    this.code = code;
  }
}

/** One JS event captured by [WebViewDriver.captureEvents]. */
export interface CapturedEvent {
  plugin: string;
  name: string;
  payload: any;
  /** page `Date.now()` when the listener ran */
  receivedAt: number;
}

export interface ConnectOptions {
  /** wait for the app's WebView DevTools socket (default 30000 ms) */
  timeoutMs?: number;
}

/**
 * Drives the app's WebView through the Chrome DevTools Protocol: `adb forward tcp:0
 * localabstract:webview_devtools_remote_<pid>`, `GET /json`, then the page's `webSocketDebuggerUrl` with Node's global
 * `WebSocket`. Debug builds of Capacitor apps enable WebView debugging by default.
 */
export class WebViewDriver {
  /** Connects to the WebView of [appId]'s running main process (the app must be launched). */
  static async connect(adb: Adb, appId: string, options?: ConnectOptions): Promise<WebViewDriver> {
    return notImplemented('WebViewDriver.connect');
  }

  /**
   * `Runtime.evaluate` with `returnByValue` and `awaitPromise`; resolves with the JSON value. A thrown/rejected
   * expression rejects with an Error carrying the page's message.
   */
  async evaluate<T>(expression: string, options?: { timeoutMs?: number }): Promise<T> {
    return notImplemented('WebViewDriver.evaluate');
  }

  /**
   * `Capacitor.Plugins[plugin][method](args)`; resolves with the result, or rejects with [PluginCallError] (its `code`
   * is the plugin's rejection code, e.g. 'NOT_READY').
   */
  async callPlugin<T>(plugin: string, method: string, args?: unknown): Promise<T> {
    return notImplemented('WebViewDriver.callPlugin');
  }

  /** Adds page listeners for [names] on `Capacitor.Plugins[plugin]` that buffer every event (idempotent per name). */
  async captureEvents(plugin: string, names: readonly string[]): Promise<void> {
    return notImplemented('WebViewDriver.captureEvents');
  }

  /** Returns and clears the buffered events, oldest first. */
  async drainEvents(): Promise<CapturedEvent[]> {
    return notImplemented('WebViewDriver.drainEvents');
  }

  /** `location.reload()` and wait for the page's `Capacitor` global. */
  async reload(): Promise<void> {
    return notImplemented('WebViewDriver.reload');
  }

  /** Closes the socket and removes the adb forward. */
  async close(): Promise<void> {
    return notImplemented('WebViewDriver.close');
  }
}
