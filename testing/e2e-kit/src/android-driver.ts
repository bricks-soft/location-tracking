import type { Adb } from './adb.ts';
import { PERMISSIONS, type AppUnderTest } from './app.ts';
import type { PlatformDriver, ResetOptions, RouteHandle } from './driver.ts';
import { EMULATOR_HOST } from './env.ts';
import type { LatLon } from './types.ts';
import { sleep } from './util.ts';
import type { CapturedEvent } from './webview.ts';

export const PLUGIN_NAME = 'LocationTracking';
const HOLD_INTERVAL_MS = 2000;

export class AndroidDriver implements PlatformDriver {
  readonly platform = 'android' as const;
  readonly hostAddress = EMULATOR_HOST;
  readonly app: AppUnderTest;
  private hold: { controller: AbortController; task: Promise<void> } | undefined;

  constructor(app: AppUnderTest) {
    this.app = app;
  }

  get adb(): Adb {
    return this.app.adb;
  }

  get appId(): string {
    return this.app.appId;
  }

  async reset(options: ResetOptions): Promise<void> {
    await this.stopHold();
    const permissions =
      options.location === 'always'
        ? 'all'
        : options.location === 'none'
          ? 'none'
          : [PERMISSIONS.coarse, PERMISSIONS.fine, PERMISSIONS.activity, PERMISSIONS.notifications];
    await this.app.prepare({ permissions, batteryExempt: true });
    if (options.position) await this.setPosition(options.position);
  }

  async launch(): Promise<number> {
    await this.app.launch();
    return this.app.waitForProcess();
  }

  async kill(): Promise<void> {
    await this.app.closeWebView();
    await this.adb.killHard(this.appId);
  }

  async pid(): Promise<number | null> {
    return this.app.pid();
  }

  async call<T = any>(method: string, options: Record<string, unknown> = {}, timeoutMs = 60_000): Promise<T> {
    const view = await this.app.webView();
    return view.callPlugin<T>(PLUGIN_NAME, method, options, { timeoutMs });
  }

  async captureEvents(names: readonly string[]): Promise<void> {
    await (await this.app.webView()).captureEvents(PLUGIN_NAME, names);
  }

  async drainEvents(): Promise<CapturedEvent[]> {
    return (await this.app.webView()).drainEvents();
  }

  async setPosition(point: LatLon): Promise<void> {
    await this.stopHold();
    await this.adb.geoFix(point.lat, point.lon);
    const controller = new AbortController();
    const task = (async () => {
      while (!controller.signal.aborted) {
        try {
          await sleep(HOLD_INTERVAL_MS, controller.signal);
          await this.adb.geoFix(point.lat, point.lon);
        } catch {
          if (controller.signal.aborted) return;
        }
      }
    })();
    this.hold = { controller, task };
  }

  async drive(points: readonly LatLon[], speedMps: number, signal?: AbortSignal): Promise<void> {
    await this.stopHold();
    await this.app.commands.otherAppLocation(true);
    try {
      await this.adb.playRoute(points, signal ? { speedMps, signal } : { speedMps });
    } finally {
      await this.app.commands.otherAppLocation(false).catch(() => undefined);
    }
    await this.setPosition(points[points.length - 1]!);
  }

  async startRoute(points: readonly LatLon[], speedMps: number): Promise<RouteHandle> {
    await this.stopHold();
    await this.app.commands.otherAppLocation(true);
    const controller = new AbortController();
    const loop = this.adb.playRouteLoop(points, { speedMps, signal: controller.signal }).catch(() => undefined);
    return {
      stop: async () => {
        controller.abort();
        await loop;
        await this.app.commands.otherAppLocation(false).catch(() => undefined);
      },
    };
  }

  async background(): Promise<void> {
    await this.adb.keyHome();
  }

  async foreground(): Promise<void> {
    await this.adb.startApp(this.appId);
  }

  async restartDevice(): Promise<void> {
    await this.stopHold();
    await this.app.closeWebView();
    await this.adb.reboot();
    await this.adb.screenOn();
  }

  async readAppFile(path: string): Promise<string | null> {
    try {
      return await this.adb.runAsCat(this.appId, path);
    } catch {
      return null;
    }
  }

  private async stopHold(): Promise<void> {
    const hold = this.hold;
    this.hold = undefined;
    if (!hold) return;
    hold.controller.abort();
    await hold.task;
  }

  async dispose(): Promise<void> {
    await this.stopHold();
    await this.app.dispose();
  }
}
