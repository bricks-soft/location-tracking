import { spawn, type ChildProcess } from 'node:child_process';
import { createWriteStream, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import type { LatLon } from './types.ts';

export interface SimctlOptions {
  device: string;
  xcrun?: string;
  timeoutMs?: number;
}

export interface SimctlResult {
  stdout: string;
  stderr: string;
  code: number;
}

export class SimctlError extends Error {
  readonly args: readonly string[];
  readonly result: SimctlResult;

  constructor(args: readonly string[], result: SimctlResult, timedOut: boolean) {
    const detail = (result.stderr || result.stdout).trim().split('\n').slice(-5).join('\n');
    super(`xcrun simctl ${args.join(' ')} ${timedOut ? 'timed out' : `exited with ${result.code}`}${detail ? `: ${detail}` : ''}`);
    this.name = 'SimctlError';
    this.args = args;
    this.result = result;
  }
}

export interface SimRuntime {
  identifier: string;
  version: string;
  major: number;
}

export function parseRuntime(devicesJson: string, udid: string): SimRuntime | undefined {
  const devices = (JSON.parse(devicesJson) as { devices: Record<string, Array<{ udid: string }>> }).devices;
  for (const [identifier, list] of Object.entries(devices)) {
    if (!list.some((device) => device.udid === udid)) continue;
    const numbers = /iOS[-.](\d+)(?:[-.](\d+))?(?:[-.](\d+))?/.exec(identifier);
    if (!numbers) return undefined;
    const parts = numbers.slice(1).filter((part): part is string => part !== undefined);
    return { identifier, version: parts.join('.'), major: Number.parseInt(parts[0]!, 10) };
  }
  return undefined;
}

export function parseLaunchPid(output: string): number | undefined {
  const match = /:\s*(\d+)\s*$/m.exec(output.trim());
  return match ? Number.parseInt(match[1]!, 10) : undefined;
}

export class Simctl {
  readonly device: string;
  readonly xcrun: string;
  readonly timeoutMs: number;

  constructor(options: SimctlOptions) {
    this.device = options.device;
    this.xcrun = options.xcrun ?? 'xcrun';
    this.timeoutMs = options.timeoutMs ?? 600_000;
  }

  exec(args: readonly string[], options: { timeoutMs?: number; allowFailure?: boolean } = {}): Promise<SimctlResult> {
    const timeoutMs = options.timeoutMs ?? this.timeoutMs;
    return new Promise((resolve, reject) => {
      const child = spawn(this.xcrun, ['simctl', ...args], { stdio: ['ignore', 'pipe', 'pipe'] });
      let stdout = '';
      let stderr = '';
      let timedOut = false;
      child.stdout.on('data', (chunk: Buffer) => (stdout += chunk.toString('utf8')));
      child.stderr.on('data', (chunk: Buffer) => (stderr += chunk.toString('utf8')));
      const timer = setTimeout(() => {
        timedOut = true;
        child.kill('SIGKILL');
      }, timeoutMs);
      child.on('error', (error) => {
        clearTimeout(timer);
        reject(error);
      });
      child.on('close', (code) => {
        clearTimeout(timer);
        const result = { stdout, stderr, code: code ?? -1 };
        if ((result.code !== 0 || timedOut) && !options.allowFailure) reject(new SimctlError(args, result, timedOut));
        else resolve(result);
      });
    });
  }

  async output(args: readonly string[], timeoutMs?: number): Promise<string> {
    return (await this.exec(args, timeoutMs === undefined ? {} : { timeoutMs })).stdout.trim();
  }

  async boot(): Promise<void> {
    await this.exec(['boot', this.device], { allowFailure: true, timeoutMs: 300_000 });
    await this.exec(['bootstatus', this.device, '-b'], { timeoutMs: 600_000 });
  }

  async shutdown(): Promise<void> {
    await this.exec(['shutdown', this.device], { allowFailure: true, timeoutMs: 300_000 });
  }

  async runtime(): Promise<SimRuntime> {
    const runtime = parseRuntime(await this.output(['list', 'devices', '-j']), await this.udid());
    if (!runtime) throw new Error(`no iOS runtime found for simulator ${this.device}`);
    return runtime;
  }

  async udid(): Promise<string> {
    if (this.device !== 'booted') return this.device;
    const devices = JSON.parse(await this.output(['list', 'devices', 'booted', '-j'])) as { devices: Record<string, Array<{ udid: string }>> };
    const booted = Object.values(devices.devices).flat();
    if (booted.length !== 1) throw new Error(`E2E_SIMULATOR is not set and ${booted.length} simulators are booted`);
    return booted[0]!.udid;
  }

  async install(app: string): Promise<void> {
    await this.exec(['install', this.device, app], { timeoutMs: 600_000 });
  }

  async container(bundle: string, kind: 'app' | 'data' = 'data'): Promise<string> {
    return this.output(['get_app_container', this.device, bundle, kind]);
  }

  async launch(bundle: string, args: readonly string[] = [], options: { terminate?: boolean } = {}): Promise<number> {
    const flags = options.terminate ? ['--terminate-running-process'] : [];
    const out = await this.output(['launch', ...flags, this.device, bundle, ...args], 300_000);
    const pid = parseLaunchPid(out);
    if (pid === undefined) throw new Error(`simctl launch printed no pid: ${out}`);
    return pid;
  }

  async terminate(bundle: string): Promise<void> {
    await this.exec(['terminate', this.device, bundle], { allowFailure: true, timeoutMs: 60_000 });
  }

  async privacy(action: 'grant' | 'revoke' | 'reset', service: string, bundle: string): Promise<void> {
    await this.exec(['privacy', this.device, action, service, bundle], { timeoutMs: 600_000 });
  }

  async setLocation(point: LatLon): Promise<void> {
    await this.exec(['location', this.device, 'set', `${point.lat.toFixed(7)},${point.lon.toFixed(7)}`], { timeoutMs: 60_000 });
  }

  async clearLocation(): Promise<void> {
    await this.exec(['location', this.device, 'clear'], { allowFailure: true, timeoutMs: 60_000 });
  }

  startLocationScenario(points: readonly LatLon[], speedMps: number, log: string): ChildProcess {
    const waypoints = points.map((point) => `${point.lat.toFixed(7)},${point.lon.toFixed(7)}`);
    mkdirSync(dirname(log), { recursive: true });
    const out = createWriteStream(log, { flags: 'a' });
    out.on('error', () => {});
    const child = spawn(this.xcrun, ['simctl', 'location', this.device, 'start', `--speed=${speedMps}`, '--interval=1', ...waypoints], {
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    child.stdout?.pipe(out);
    child.stderr?.pipe(out);
    child.on('error', () => {});
    return child;
  }

  async screenshot(file: string): Promise<void> {
    await this.exec(['io', this.device, 'screenshot', file], { timeoutMs: 30_000 });
  }

  async log(minutes: number, predicate: string): Promise<string> {
    const result = await this.exec(['spawn', this.device, 'log', 'show', '--last', `${minutes}m`, '--style', 'compact', '--predicate', predicate], {
      timeoutMs: 120_000,
      allowFailure: true,
    });
    return result.stdout + result.stderr;
  }
}
