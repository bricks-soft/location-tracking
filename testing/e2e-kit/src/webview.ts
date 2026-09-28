// WebView driver of the e2e kit (Unit 7): Chrome DevTools Protocol over adb. Contract: docs/e2e/architecture.md §8.
import { randomUUID } from 'node:crypto';
import type { Adb } from './adb.ts';
import { waitUntil } from './util.ts';

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
  /** wait for the app's WebView DevTools socket, the page and the `Capacitor` global (default 30000 ms) */
  timeoutMs?: number;
}

/** One entry of the DevTools `GET /json` list. */
export interface DevToolsTarget {
  id: string;
  type: string;
  url: string;
  title?: string;
  webSocketDebuggerUrl?: string;
}

/** Name of the WebView DevTools socket of [pid] in `/proc/net/unix` output (`@webview_devtools_remote_<pid>`), if present. */
export function findDevToolsSocket(procNetUnix: string, pid: number): string | undefined {
  const name = `webview_devtools_remote_${pid}`;
  for (const line of procNetUnix.split('\n')) {
    if (line.trim().endsWith(`@${name}`)) return name;
  }
  return undefined;
}

/**
 * The app's page among the DevTools targets: a `page` whose URL starts with `https://localhost` (Capacitor's default),
 * `http://localhost` or `capacitor://localhost`; else any other `page` that is not `about:blank`.
 */
export function pickAppTarget(targets: readonly DevToolsTarget[]): DevToolsTarget | undefined {
  const pages = targets.filter((t) => t.type === 'page' && typeof t.url === 'string');
  return (
    pages.find((t) => /^(https?|capacitor):\/\/localhost([/:]|$)/.test(t.url)) ??
    pages.find((t) => t.url !== '' && t.url !== 'about:blank')
  );
}

interface Pending {
  method: string;
  resolve: (value: any) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
}

const CAPACITOR_READY = `typeof globalThis.Capacitor !== 'undefined' && !!globalThis.Capacitor.Plugins`;

function openWebSocket(url: string, timeoutMs: number): Promise<WebSocket> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    const timer = setTimeout(() => {
      ws.close();
      reject(new Error(`WebSocket ${url} did not open within ${timeoutMs} ms`));
    }, timeoutMs);
    ws.addEventListener('open', () => {
      clearTimeout(timer);
      resolve(ws);
    });
    ws.addEventListener('error', () => {
      clearTimeout(timer);
      reject(new Error(`WebSocket ${url} failed to open`));
    });
  });
}

/**
 * Drives the app's WebView through the Chrome DevTools Protocol: finds `@webview_devtools_remote_<pid>` in
 * `/proc/net/unix`, `adb forward tcp:0 localabstract:webview_devtools_remote_<pid>`, `GET /json`, then the page's
 * `webSocketDebuggerUrl` with Node's global `WebSocket`. Debug builds of Capacitor apps enable WebView debugging by
 * default. One driver belongs to one app process; [AppUnderTest.webView] reconnects after the pid changed.
 */
export class WebViewDriver {
  readonly adb: Adb;
  readonly appId: string;
  /** pid of the app process whose WebView this driver talks to */
  readonly pid: number;
  /** local port of the adb forward */
  readonly port: number;
  /** URL of the page when the driver connected */
  readonly pageUrl: string;
  private readonly ws: WebSocket;
  private nextId = 1;
  private readonly pending = new Map<number, Pending>();
  private isClosed = false;
  private forwardRemoved = false;

  private constructor(adb: Adb, appId: string, pid: number, port: number, pageUrl: string, ws: WebSocket) {
    this.adb = adb;
    this.appId = appId;
    this.pid = pid;
    this.port = port;
    this.pageUrl = pageUrl;
    this.ws = ws;
    ws.addEventListener('message', (event) => this.onMessage(event.data));
    ws.addEventListener('close', () => this.onClosed());
    ws.addEventListener('error', () => this.onClosed());
  }

  /** Connects to the WebView of [appId]'s running main process (the app must be launched); retries until the socket exists. */
  static async connect(adb: Adb, appId: string, options: ConnectOptions = {}): Promise<WebViewDriver> {
    const timeoutMs = options.timeoutMs ?? 30_000;
    const deadline = Date.now() + timeoutMs;
    const remaining = () => Math.max(0, deadline - Date.now());
    const found = await waitUntil(
      async () => {
        const pid = await adb.pidof(appId);
        if (pid === null) return false;
        const socket = findDevToolsSocket(await adb.shell('cat /proc/net/unix'), pid);
        return socket ? { pid, socket } : false;
      },
      { timeoutMs, intervalMs: 500, message: `the WebView DevTools socket of ${appId} (webview_devtools_remote_<pid> in /proc/net/unix)` },
    );
    const port = await adb.forward(`localabstract:${found.socket}`);
    let target: DevToolsTarget;
    let ws: WebSocket;
    try {
      target = await waitUntil(
        async () => {
          const response = await fetch(`http://127.0.0.1:${port}/json`, { signal: AbortSignal.timeout(5000) });
          if (!response.ok) throw new Error(`GET /json answered ${response.status}`);
          return pickAppTarget((await response.json()) as DevToolsTarget[]);
        },
        { timeoutMs: remaining(), intervalMs: 500, message: `a WebView page of ${appId} in the DevTools target list` },
      );
      let path = `/devtools/page/${target.id}`;
      if (target.webSocketDebuggerUrl) {
        try {
          path = new URL(target.webSocketDebuggerUrl).pathname;
        } catch {
          // keep the id-based path
        }
      }
      ws = await openWebSocket(`ws://127.0.0.1:${port}${path}`, Math.max(1000, remaining()));
    } catch (error) {
      // Cleanup failures must not replace the connection error.
      await adb.removeForward(port).catch(() => {});
      throw error;
    }
    const driver = new WebViewDriver(adb, appId, found.pid, port, target.url, ws);
    try {
      await driver.waitForCapacitor(Math.max(1000, remaining()));
    } catch (error) {
      await driver.close().catch(() => {});
      throw error;
    }
    return driver;
  }

  /** True after [close] or when the DevTools connection dropped (e.g. the process died). */
  get closed(): boolean {
    return this.isClosed;
  }

  private onMessage(data: unknown): void {
    let message: { id?: number; result?: unknown; error?: { message?: string; code?: number } };
    try {
      message = JSON.parse(typeof data === 'string' ? data : Buffer.from(data as ArrayBuffer).toString('utf8'));
    } catch {
      return;
    }
    if (typeof message.id !== 'number') return; // a CDP event; the kit enables no domains
    const pending = this.pending.get(message.id);
    if (!pending) return;
    this.pending.delete(message.id);
    clearTimeout(pending.timer);
    if (message.error) pending.reject(new Error(`CDP ${pending.method} failed: ${message.error.message ?? JSON.stringify(message.error)}`));
    else pending.resolve(message.result);
  }

  private onClosed(): void {
    this.isClosed = true;
    for (const [id, pending] of this.pending) {
      clearTimeout(pending.timer);
      pending.reject(new Error(`WebView DevTools connection closed during ${pending.method} (the app process died or the page closed)`));
      this.pending.delete(id);
    }
  }

  /** Sends one CDP command and resolves with its `result`. */
  async cdp<T = any>(method: string, params: Record<string, unknown> = {}, timeoutMs = 30_000): Promise<T> {
    if (this.isClosed) throw new Error(`WebView DevTools connection of ${this.appId} (pid ${this.pid}) is closed`);
    const id = this.nextId++;
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`CDP ${method} timed out after ${timeoutMs} ms`));
      }, timeoutMs);
      this.pending.set(id, { method, resolve, reject, timer });
      try {
        this.ws.send(JSON.stringify({ id, method, params }));
      } catch (error) {
        clearTimeout(timer);
        this.pending.delete(id);
        reject(error instanceof Error ? error : new Error(String(error)));
      }
    });
  }

  /**
   * `Runtime.evaluate` with `returnByValue` and `awaitPromise`; resolves with the JSON value. A thrown/rejected
   * expression rejects with an Error carrying the page's message. Default timeout 30 s.
   */
  async evaluate<T>(expression: string, options: { timeoutMs?: number } = {}): Promise<T> {
    const timeoutMs = options.timeoutMs ?? 30_000;
    const result = await this.cdp<{
      result?: { type?: string; value?: unknown; description?: string };
      exceptionDetails?: { text?: string; exception?: { description?: string; value?: unknown } };
    }>('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true, userGesture: true }, timeoutMs);
    if (result.exceptionDetails) {
      const details = result.exceptionDetails;
      const text =
        details.exception?.description ??
        (details.exception?.value !== undefined ? JSON.stringify(details.exception.value) : undefined) ??
        details.text ??
        'unknown error';
      throw new Error(`page evaluation failed: ${text}`);
    }
    return result.result?.value as T;
  }

  /**
   * `Capacitor.Plugins[plugin][method](args)` (registering the plugin with `Capacitor.registerPlugin` when the page has
   * not); resolves with the result, or rejects with [PluginCallError] (its `code` is the plugin's rejection code, e.g.
   * 'NOT_READY').
   */
  async callPlugin<T>(plugin: string, method: string, args?: unknown, options: { timeoutMs?: number } = {}): Promise<T> {
    const argText = args === undefined ? '' : JSON.stringify(args);
    const expression = `(async () => {
  const cap = globalThis.Capacitor;
  if (!cap) return { e2e: 'error', message: 'Capacitor is not defined on the page', code: 'NO_CAPACITOR' };
  const plugin = (cap.Plugins && cap.Plugins[${JSON.stringify(plugin)}]) ||
    (typeof cap.registerPlugin === 'function' ? cap.registerPlugin(${JSON.stringify(plugin)}) : undefined);
  if (!plugin) return { e2e: 'error', message: 'plugin ' + ${JSON.stringify(plugin)} + ' is not available', code: 'UNAVAILABLE' };
  try {
    const value = await plugin[${JSON.stringify(method)}](${argText});
    return value === undefined ? { e2e: 'ok', undefined: true } : { e2e: 'ok', value };
  } catch (e) {
    return { e2e: 'error', message: String((e && e.message) || e),
             code: e && e.code !== undefined && e.code !== null ? String(e.code) : undefined };
  }
})()`;
    const outcome = await this.evaluate<{ e2e: 'ok' | 'error'; value?: T; undefined?: boolean; message?: string; code?: string }>(
      expression,
      options,
    );
    if (!outcome || outcome.e2e !== 'ok') {
      const message = outcome?.message ?? 'no result';
      throw new PluginCallError(`${plugin}.${method} rejected: ${message}`, outcome?.code);
    }
    return (outcome.undefined ? undefined : outcome.value) as T;
  }

  /**
   * Adds page listeners for [names] on `Capacitor.Plugins[plugin]` that buffer every event in `window.__e2eEvents`
   * (idempotent per plugin and name). A page reload removes them: call it again after [reload].
   */
  async captureEvents(plugin: string, names: readonly string[]): Promise<void> {
    const expression = `(async () => {
  const pluginName = ${JSON.stringify(plugin)};
  const cap = globalThis.Capacitor;
  const plugin = cap && ((cap.Plugins && cap.Plugins[pluginName]) ||
    (typeof cap.registerPlugin === 'function' ? cap.registerPlugin(pluginName) : undefined));
  if (!plugin) throw new Error('plugin ' + pluginName + ' is not available');
  window.__e2eEvents = window.__e2eEvents || [];
  window.__e2eHandles = window.__e2eHandles || {};
  for (const name of ${JSON.stringify(names)}) {
    const key = pluginName + ':' + name;
    if (window.__e2eHandles[key]) continue;
    window.__e2eHandles[key] = true;
    try {
      window.__e2eHandles[key] = await plugin.addListener(name, (payload) => {
        window.__e2eEvents.push({ plugin: pluginName, name, payload, receivedAt: Date.now() });
      });
    } catch (error) {
      delete window.__e2eHandles[key];
      throw error;
    }
  }
  return true;
})()`;
    await this.evaluate<boolean>(expression);
  }

  /** Returns and clears the buffered events, oldest first. */
  async drainEvents(): Promise<CapturedEvent[]> {
    const events = await this.evaluate<CapturedEvent[] | undefined>(
      `(() => { const events = window.__e2eEvents || []; window.__e2eEvents = []; return events; })()`,
    );
    return events ?? [];
  }

  /** `Page.reload` and waits until the new page has the `Capacitor` global (default 30 s). */
  async reload(options: { timeoutMs?: number } = {}): Promise<void> {
    const marker = randomUUID();
    await this.evaluate(`(window.__e2eReloadMarker = ${JSON.stringify(marker)}, true)`);
    await this.cdp('Page.reload', { ignoreCache: false });
    await waitUntil(
      () =>
        this.evaluate<boolean>(
          `window.__e2eReloadMarker !== ${JSON.stringify(marker)} && document.readyState !== 'loading' && ${CAPACITOR_READY}`,
          { timeoutMs: 5000 },
        ),
      { timeoutMs: options.timeoutMs ?? 30_000, intervalMs: 250, message: `the reloaded page of ${this.appId} (Capacitor global)` },
    );
  }

  /** Waits until the page has the `Capacitor` global. */
  async waitForCapacitor(timeoutMs = 30_000): Promise<void> {
    await waitUntil(() => this.evaluate<boolean>(CAPACITOR_READY, { timeoutMs: 5000 }), {
      timeoutMs,
      intervalMs: 250,
      message: `the Capacitor global in the WebView of ${this.appId}`,
    });
  }

  /** Closes the socket and removes the adb forward (idempotent). */
  async close(): Promise<void> {
    this.onClosed();
    try {
      this.ws.close();
    } catch {
      // already closed
    }
    if (!this.forwardRemoved) {
      this.forwardRemoved = true;
      await this.adb.removeForward(this.port);
    }
  }
}
