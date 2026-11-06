import type { ChildProcess } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { mkdir, readdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { routeLengthMeters } from './assertions.ts';
import type { PlatformDriver, ResetOptions, RouteHandle } from './driver.ts';
import { Simctl, type SimRuntime } from './simctl.ts';
import type { LatLon, StateJson, WireRecord } from './types.ts';
import { errorText, sleep, waitUntil } from './util.ts';
import { PluginCallError, type CapturedEvent } from './webview.ts';

export const IOS_SIMULATOR_HOST = '127.0.0.1';
export const IOS_SCENARIO_ARGUMENT = '--e2e-scenario';
export const IOS_WEB_SMOKE_ARGUMENT = '--e2e-web-smoke';
export const IOS_CHANNEL_DIR = 'Library/Application Support/E2E';
const SETTINGS_BUNDLE = 'com.apple.Preferences';

export interface IosCheckIn {
  identifier: string;
  interval: number | null;
  title: string;
}

export interface IosSnapshot {
  state: StateJson & { lastStopReason?: string | null };
  locations: WireRecord[];
  runtime: Record<string, any>;
  pending: IosCheckIn[];
  delivered: number;
  audit: WireRecord[];
  appState: 'foreground' | 'background';
}

export interface IosWebSmokeResult {
  success: boolean;
  checks: string[];
  error?: string;
  code?: string | null;
  view?: { width: number; height: number; windowWidth: number; windowHeight: number; visible: boolean };
}

interface ChannelResponse {
  id: string;
  success: boolean;
  value?: unknown;
  code?: string;
  error?: string;
}

export interface IosDriverOptions {
  simctl: Simctl;
  appId: string;
  logDir: string;
}

export function isAlive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return (error as NodeJS.ErrnoException).code === 'EPERM';
  }
}

export class IosDriver implements PlatformDriver {
  readonly platform = 'ios' as const;
  readonly hostAddress = IOS_SIMULATOR_HOST;
  readonly appId: string;
  readonly simctl: Simctl;
  readonly logDir: string;
  private dataDir: string | undefined;
  private routes = new Set<ChildProcess>();
  private crashMark = Date.now();

  constructor(options: IosDriverOptions) {
    this.simctl = options.simctl;
    this.appId = options.appId;
    this.logDir = options.logDir;
  }

  async runtime(): Promise<SimRuntime> {
    return this.simctl.runtime();
  }

  private async data(): Promise<string> {
    this.dataDir ??= await this.simctl.container(this.appId, 'data');
    return this.dataDir;
  }

  private async channel(): Promise<string> {
    const dir = join(await this.data(), IOS_CHANNEL_DIR);
    await mkdir(dir, { recursive: true });
    return dir;
  }

  async reset(options: ResetOptions): Promise<void> {
    await this.stopRoutes();
    await this.simctl.boot();
    await this.simctl.terminate(this.appId);
    this.dataDir = undefined;
    await this.simctl.privacy('reset', 'location', this.appId);
    await this.simctl.privacy('reset', 'location-always', this.appId);
    if (options.location === 'always') await this.simctl.privacy('grant', 'location-always', this.appId);
    if (options.location === 'whenInUse') await this.simctl.privacy('grant', 'location', this.appId);
    if (options.position) await this.setPosition(options.position);
  }

  async launch(): Promise<number> {
    let failure: unknown;
    for (let attempt = 1; attempt <= 3; attempt++) {
      try {
        return await this.launchOnce();
      } catch (error) {
        failure = error;
        await this.log('launch.log', `attempt ${attempt} failed: ${errorText(error)}`);
      }
    }
    throw new Error(`the scenario host did not become ready after 3 launches: ${errorText(failure)}`);
  }

  private async launchOnce(): Promise<number> {
    const dir = await this.channel();
    for (const name of ['ready.json', 'command.json', 'response.json']) await rm(join(dir, name), { force: true });
    const pid = await this.simctl.launch(this.appId, [IOS_SCENARIO_ARGUMENT], { terminate: true });
    await this.log('launch.log', `launched pid ${pid}`);
    await waitUntil(
      async () => {
        const ready = await this.readJson<{ pid?: number }>(join(dir, 'ready.json'));
        if (ready?.pid === pid) return true;
        if (!isAlive(pid)) throw new Error(`the app (pid ${pid}) exited before it was ready`);
        return false;
      },
      { timeoutMs: 300_000, intervalMs: 500, message: `ready.json of pid ${pid}` },
    );
    return pid;
  }

  async kill(): Promise<void> {
    const pid = await this.pid();
    if (pid === null) return;
    process.kill(pid, 'SIGKILL');
    await waitUntil(() => !isAlive(pid), { timeoutMs: 15_000, intervalMs: 200, message: `pid ${pid} to exit after SIGKILL` });
  }

  async pid(): Promise<number | null> {
    const ready = await this.readJson<{ pid?: number }>(join(await this.channel(), 'ready.json'));
    return ready?.pid !== undefined && isAlive(ready.pid) ? ready.pid : null;
  }

  async call<T = any>(method: string, options: Record<string, unknown> = {}, timeoutMs = 60_000): Promise<T> {
    const response = await this.send(method, options, timeoutMs);
    if (!response.success) throw new PluginCallError(`${method} rejected: ${response.error ?? 'no message'}`, response.code);
    return response.value as T;
  }

  private async send(method: string, options: Record<string, unknown>, timeoutMs: number): Promise<ChannelResponse> {
    const dir = await this.channel();
    const id = randomUUID();
    const temporary = join(dir, 'command.tmp');
    await writeFile(temporary, JSON.stringify({ id, method, options }));
    await rename(temporary, join(dir, 'command.json'));
    try {
      return await waitUntil(
        async () => {
          const response = await this.readJson<ChannelResponse>(join(dir, 'response.json'));
          return response?.id === id ? response : undefined;
        },
        { timeoutMs, intervalMs: 250, message: `the scenario host's response to ${method}` },
      );
    } finally {
      await rm(join(dir, 'command.json'), { force: true });
    }
  }

  async captureEvents(names: readonly string[]): Promise<void> {
    await this.call('captureEvents', { names });
  }

  async drainEvents(): Promise<CapturedEvent[]> {
    const events = await this.call<Array<{ name: string; payload: any; receivedAt: number }>>('drainEvents');
    return events.map((event) => ({ plugin: 'LocationTracking', ...event }));
  }

  async snapshot(): Promise<IosSnapshot> {
    return this.call<IosSnapshot>('snapshot');
  }

  async willTerminate(): Promise<StateJson> {
    return this.call<StateJson>('willTerminate');
  }

  async setPosition(point: LatLon): Promise<void> {
    await this.simctl.setLocation(point);
  }

  async drive(points: readonly LatLon[], speedMps: number, signal?: AbortSignal): Promise<void> {
    const route = this.simctl.startLocationScenario(points, speedMps, join(this.logDir, 'route.log'));
    this.routes.add(route);
    try {
      await sleep((routeLengthMeters(points) / speedMps) * 1000 + 5000, signal);
    } finally {
      await this.stopRoute(route);
    }
    await this.setPosition(points[points.length - 1]!);
  }

  async startRoute(points: readonly LatLon[], speedMps: number): Promise<RouteHandle> {
    const route = this.simctl.startLocationScenario(points, speedMps, join(this.logDir, 'route.log'));
    this.routes.add(route);
    return {
      stop: async () => {
        await this.stopRoute(route);
        await this.simctl.clearLocation();
      },
    };
  }

  private async stopRoute(route: ChildProcess): Promise<void> {
    this.routes.delete(route);
    if (route.exitCode === null && route.signalCode === null) {
      route.kill('SIGTERM');
      await new Promise<void>((resolve) => {
        const timer = setTimeout(() => {
          route.kill('SIGKILL');
          resolve();
        }, 5000);
        route.once('exit', () => {
          clearTimeout(timer);
          resolve();
        });
      });
    }
  }

  private async stopRoutes(): Promise<void> {
    for (const route of [...this.routes]) await this.stopRoute(route);
  }

  async background(): Promise<void> {
    await this.simctl.launch(SETTINGS_BUNDLE);
  }

  async foreground(): Promise<void> {
    await this.simctl.launch(this.appId, [IOS_SCENARIO_ARGUMENT]);
  }

  async restartDevice(): Promise<void> {
    await this.stopRoutes();
    await this.simctl.shutdown();
    await this.simctl.boot();
    this.dataDir = undefined;
  }

  async readAppFile(path: string): Promise<string | null> {
    try {
      return await readFile(join(await this.data(), path), 'utf8');
    } catch {
      return null;
    }
  }

  async webSmoke(timeoutMs = 600_000): Promise<IosWebSmokeResult> {
    const support = join(await this.data(), 'Library/Application Support');
    await mkdir(support, { recursive: true });
    for (const name of ['e2e-web-smoke.json', 'e2e-web-state.json', 'e2e-web-smoke.png']) await rm(join(support, name), { force: true });
    const pid = await this.simctl.launch(this.appId, [IOS_WEB_SMOKE_ARGUMENT], { terminate: true });
    return waitUntil(
      async () => {
        const result = await this.readJson<IosWebSmokeResult>(join(support, 'e2e-web-smoke.json'));
        if (result) return result;
        if (!isAlive(pid)) throw new Error(`the app (pid ${pid}) exited before the WebView smoke finished`);
        return undefined;
      },
      { timeoutMs, intervalMs: 500, message: 'e2e-web-smoke.json' },
    );
  }

  markCrashes(): void {
    this.crashMark = Date.now();
  }

  async crashReports(): Promise<string[]> {
    const dir = join(homedir(), 'Library/Logs/DiagnosticReports');
    let names: string[];
    try {
      names = await readdir(dir);
    } catch {
      return [];
    }
    const found: string[] = [];
    for (const name of names) {
      if (!/^App[-_].*\.ips$/.test(name)) continue;
      const file = join(dir, name);
      const info = await stat(file).catch(() => undefined);
      if (!info || info.mtimeMs < this.crashMark) continue;
      const header = (await readFile(file, 'utf8').catch(() => '')).split('\n', 1)[0] ?? '';
      if (/"bug_type"\s*:\s*"309"/.test(header)) found.push(file);
    }
    return found;
  }

  async assertNoCrash(): Promise<void> {
    const reports = await this.crashReports();
    if (reports.length > 0) throw new Error(`the app crashed during the scenario: ${reports.join(', ')}`);
  }

  async collect(dir: string, write: (name: string, text: string) => Promise<string>): Promise<string[]> {
    const written: string[] = [];
    const errors: string[] = [];
    const item = async (name: string, produce: () => Promise<string | undefined>) => {
      try {
        const path = await produce();
        if (path) written.push(path);
      } catch (error) {
        errors.push(`${name}: ${errorText(error)}`);
      }
    };
    await item('snapshot.json', async () => write('snapshot.json', `${JSON.stringify(await this.call('snapshot', {}, 20_000), null, 2)}\n`));
    await item('app.log', async () =>
      write('app.log', await this.simctl.log(15, 'process == "App" OR process == "locationd" OR process == "SpringBoard" OR process == "runningboardd"')),
    );
    await item('screenshot.png', async () => {
      await mkdir(dir, { recursive: true });
      const file = join(dir, 'screenshot.png');
      await this.simctl.screenshot(file);
      return file;
    });
    for (const report of await this.crashReports()) {
      await item(report, async () => write(`crash-${report.split('/').pop()}`, await readFile(report, 'utf8')));
    }
    if (errors.length > 0) written.push(await write('collect-errors.txt', `${errors.join('\n')}\n`));
    return written;
  }

  async dispose(): Promise<void> {
    await this.stopRoutes();
  }

  private async readJson<T>(file: string): Promise<T | undefined> {
    try {
      return JSON.parse(await readFile(file, 'utf8')) as T;
    } catch {
      return undefined;
    }
  }

  private async log(name: string, line: string): Promise<void> {
    try {
      await mkdir(this.logDir, { recursive: true });
      await writeFile(join(this.logDir, name), `${new Date().toISOString()} ${line}\n`, { flag: 'a' });
    } catch {
      return;
    }
  }
}
