// A fake WebView DevTools endpoint for the kit's tests: `GET /json` plus a minimal WebSocket server (RFC 6455 text
// frames on node:http 'upgrade', no dependencies) that answers CDP `Runtime.evaluate` by running the expression in a
// node:vm context with a fake `Capacitor` global, and `Page.reload` by creating a fresh context.
import { createHash } from 'node:crypto';
import { createServer, type Server } from 'node:http';
import type { Socket } from 'node:net';
import vm from 'node:vm';

export interface FakePage {
  context: vm.Context;
  /** listeners registered through the fake plugin's addListener, by event name */
  listeners: Map<string, Array<(payload: unknown) => void>>;
}

export interface FakeDevToolsOptions {
  /** the page URL in `/json` (default https://localhost/) */
  url?: string;
  /** omit webSocketDebuggerUrl from /json (another client attached) */
  omitWsUrl?: boolean;
  /** delay before the Capacitor global exists after a (re)load, ms */
  capacitorDelayMs?: number;
}

const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

function frame(opcode: number, payload: Buffer): Buffer {
  let header: Buffer;
  if (payload.length < 126) header = Buffer.from([0x80 | opcode, payload.length]);
  else if (payload.length < 65536) {
    header = Buffer.alloc(4);
    header[0] = 0x80 | opcode;
    header[1] = 126;
    header.writeUInt16BE(payload.length, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x80 | opcode;
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(payload.length), 2);
  }
  return Buffer.concat([header, payload]);
}

export class FakeDevTools {
  readonly options: FakeDevToolsOptions;
  private server: Server | undefined;
  private readonly sockets = new Set<Socket>();
  page: FakePage;
  /** CDP methods received, in order */
  readonly methods: string[] = [];
  /** every expression evaluated */
  readonly expressions: string[] = [];

  constructor(options: FakeDevToolsOptions = {}) {
    this.options = options;
    this.page = this.newPage();
  }

  get port(): number {
    const address = this.server?.address();
    return typeof address === 'object' && address ? address.port : 0;
  }

  /** A fresh page: `window`, and (after capacitorDelayMs) `Capacitor.Plugins.LocationTracking` with fake methods. */
  newPage(): FakePage {
    const listeners = new Map<string, Array<(payload: unknown) => void>>();
    const context = vm.createContext({ Date, Promise, JSON, Error, setTimeout, console });
    vm.runInContext('globalThis.window = globalThis; globalThis.document = { readyState: "complete" };', context);
    const install = () => {
      const plugin = {
        getState: async () => ({ enabled: true, isMoving: false }),
        start: async () => {
          const error = new Error('call ready() first') as Error & { code?: string };
          error.code = 'NOT_READY';
          throw error;
        },
        echo: async (args: unknown) => args,
        nothing: async () => undefined,
        addListener: async (name: string, callback: (payload: unknown) => void) => {
          const list = listeners.get(name) ?? [];
          list.push(callback);
          listeners.set(name, list);
          return { remove: async () => {} };
        },
      };
      (context as Record<string, unknown>)['Capacitor'] = { Plugins: { LocationTracking: plugin } };
    };
    if (this.options?.capacitorDelayMs) setTimeout(install, this.options.capacitorDelayMs);
    else install();
    return { context, listeners };
  }

  /** Calls the page's listeners of [name] with [payload] (a plugin event). */
  emit(name: string, payload: unknown): void {
    for (const callback of this.page.listeners.get(name) ?? []) callback(payload);
  }

  async start(): Promise<void> {
    const server = createServer((req, res) => {
      if (req.url === '/json' || req.url === '/json/list') {
        const port = this.port;
        const targets = [
          { id: 'SW1', type: 'service_worker', url: 'https://localhost/sw.js' },
          {
            id: 'P1',
            type: 'page',
            title: 'App',
            url: this.options.url ?? 'https://localhost/',
            ...(this.options.omitWsUrl ? {} : { webSocketDebuggerUrl: `ws://localhost/devtools/page/P1` }),
          },
        ];
        void port;
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify(targets));
        return;
      }
      res.writeHead(404);
      res.end();
    });
    server.on('upgrade', (req, socket: Socket) => {
      if (req.url !== '/devtools/page/P1') {
        socket.end('HTTP/1.1 404 Not Found\r\n\r\n');
        return;
      }
      const key = String(req.headers['sec-websocket-key'] ?? '');
      const accept = createHash('sha1').update(key + WS_GUID).digest('base64');
      socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`);
      this.sockets.add(socket);
      socket.on('close', () => this.sockets.delete(socket));
      socket.on('error', () => {});
      let buffer = Buffer.alloc(0);
      socket.on('data', (chunk: Buffer) => {
        buffer = Buffer.concat([buffer, chunk]);
        while (buffer.length >= 2) {
          const opcode = buffer[0]! & 0x0f;
          const masked = (buffer[1]! & 0x80) !== 0;
          let length = buffer[1]! & 0x7f;
          let offset = 2;
          if (length === 126) {
            if (buffer.length < 4) return;
            length = buffer.readUInt16BE(2);
            offset = 4;
          } else if (length === 127) {
            if (buffer.length < 10) return;
            length = Number(buffer.readBigUInt64BE(2));
            offset = 10;
          }
          const maskLength = masked ? 4 : 0;
          if (buffer.length < offset + maskLength + length) return;
          const mask = masked ? buffer.subarray(offset, offset + 4) : undefined;
          const payload = Buffer.from(buffer.subarray(offset + maskLength, offset + maskLength + length));
          if (mask) for (let i = 0; i < payload.length; i++) payload[i] = payload[i]! ^ mask[i % 4]!;
          buffer = buffer.subarray(offset + maskLength + length);
          if (opcode === 0x8) {
            socket.end(frame(0x8, Buffer.alloc(0)));
            return;
          }
          if (opcode === 0x9) socket.write(frame(0xa, payload));
          if (opcode === 0x1) void this.onText(socket, payload.toString('utf8'));
        }
      });
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    this.server = server;
  }

  /** Closes every WebSocket connection (like the app process dying). */
  dropConnections(): void {
    for (const socket of this.sockets) socket.destroy();
  }

  async stop(): Promise<void> {
    this.dropConnections();
    const server = this.server;
    this.server = undefined;
    if (server) {
      server.closeAllConnections();
      await new Promise<void>((resolve) => server.close(() => resolve()));
    }
  }

  private async onText(socket: Socket, text: string): Promise<void> {
    const message = JSON.parse(text) as { id: number; method: string; params?: Record<string, unknown> };
    this.methods.push(message.method);
    let reply: Record<string, unknown>;
    if (message.method === 'Runtime.evaluate') {
      const expression = String(message.params?.['expression'] ?? '');
      this.expressions.push(expression);
      if (expression.includes('__hang__')) return; // never answers
      try {
        let value = vm.runInContext(expression, this.page.context);
        if (value && typeof (value as Promise<unknown>).then === 'function') value = await value;
        reply = {
          result:
            value === undefined
              ? { result: { type: 'undefined' } }
              : { result: { type: typeof value, value: JSON.parse(JSON.stringify(value)) } },
        };
      } catch (error) {
        const description = error instanceof Error || (error && typeof error === 'object' && 'message' in error)
          ? `${(error as Error).name ?? 'Error'}: ${(error as Error).message}`
          : String(error);
        reply = {
          result: {
            result: { type: 'object', subtype: 'error', description },
            exceptionDetails: { text: 'Uncaught', exception: { type: 'object', description } },
          },
        };
      }
    } else if (message.method === 'Page.reload') {
      this.page = this.newPage();
      reply = { result: {} };
    } else {
      reply = { error: { code: -32601, message: `'${message.method}' wasn't found` } };
    }
    if (!socket.destroyed) socket.write(frame(0x1, Buffer.from(JSON.stringify({ id: message.id, ...reply }))));
  }
}
