// Failure artifacts of the e2e kit (Unit 7).
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import type { Adb } from './adb.ts';
import type { MockBackOffice } from './backoffice.ts';
import { errorText, shellQuote } from './util.ts';

export interface ArtifactsOptions {
  /** per-scenario directory, e.g. `<E2E_ARTIFACTS_DIR>/<scenario id>` */
  dir: string;
  adb: Adb;
  appId: string;
  /** the back office, if the scenario started one */
  backOffice?: () => MockBackOffice | undefined;
  /** also take a bugreport (E2E_BUGREPORT=1) */
  bugreport?: boolean;
}

/** Directory of the plugin's daily log files inside the app's data dir (Constants.LOG_DIR under filesDir). */
export const PLUGIN_LOG_DIR = 'files/location-tracking-logs';

/**
 * Keeps the lines of `dumpsys alarm` that mention [appId], each with the more indented lines that follow it (the
 * alarm's details).
 */
export function appAlarmLines(dumpsysAlarm: string, appId: string): string {
  const lines = dumpsysAlarm.split('\n');
  const out: string[] = [];
  const indent = (line: string) => line.length - line.trimStart().length;
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]!;
    if (!line.includes(appId)) continue;
    out.push(line);
    const base = indent(line);
    while (i + 1 < lines.length && lines[i + 1]!.trim() !== '' && indent(lines[i + 1]!) > base && !lines[i + 1]!.includes(appId)) {
      out.push(lines[++i]!);
    }
  }
  return out.join('\n');
}

/**
 * Failure artifacts of one scenario. [collect] writes (best effort, never throws): `reason.txt`, `logcat.txt` (main,
 * system, crash and events buffers), `crash.txt`, `dumpsys-activity-services.txt`, `dumpsys-location.txt`,
 * `dumpsys-deviceidle.txt`, `dumpsys-alarm.txt` (app lines), `dumpsys-jobscheduler.txt`, `plugin-logs/` (the plugin's
 * own log files via run-as), `backoffice.log`, `records.json`, `premise.json`, `screenshot.png` and, if enabled,
 * `bugreport.zip`. Items that failed are listed in `collect-errors.txt`.
 */
export class Artifacts {
  readonly dir: string;
  readonly options: ArtifactsOptions;

  constructor(options: ArtifactsOptions) {
    this.options = options;
    this.dir = options.dir;
  }

  /** Absolute path of [name] inside [dir]. */
  path(name: string): string {
    return `${this.dir}/${name}`;
  }

  async writeText(name: string, text: string): Promise<string> {
    const file = this.path(name);
    await mkdir(dirname(file), { recursive: true });
    await writeFile(file, text);
    return file;
  }

  async writeJson(name: string, value: unknown): Promise<string> {
    return this.writeText(name, `${JSON.stringify(value, null, 2)}\n`);
  }

  /** Collects everything listed above; resolves with the written paths. */
  async collect(reason: string): Promise<string[]> {
    const { adb, appId } = this.options;
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
    const shellText = (command: string) => adb.shell(command, { timeoutMs: 60_000 });

    await item('reason.txt', () => this.writeText('reason.txt', `${new Date().toISOString()}\n${reason}\n`));
    await item('logcat.txt', async () =>
      this.writeText('logcat.txt', await adb.logcat.dump({ buffers: ['main', 'system', 'crash', 'events'], timeoutMs: 120_000 })),
    );
    await item('crash.txt', async () => this.writeText('crash.txt', await adb.logcat.crashBuffer()));
    await item('dumpsys-activity-services.txt', async () =>
      this.writeText('dumpsys-activity-services.txt', await shellText(`dumpsys activity services ${shellQuote(appId)}`)),
    );
    await item('dumpsys-location.txt', async () => this.writeText('dumpsys-location.txt', await shellText('dumpsys location')));
    await item('dumpsys-deviceidle.txt', async () => this.writeText('dumpsys-deviceidle.txt', await shellText('dumpsys deviceidle')));
    await item('dumpsys-alarm.txt', async () =>
      this.writeText('dumpsys-alarm.txt', appAlarmLines(await shellText('dumpsys alarm'), appId)),
    );
    await item('dumpsys-jobscheduler.txt', async () =>
      this.writeText('dumpsys-jobscheduler.txt', await shellText(`dumpsys jobscheduler ${shellQuote(appId)}`)),
    );
    await item('plugin-logs', async () => {
      for (const name of await adb.runAsList(appId, PLUGIN_LOG_DIR)) {
        if (!/^[A-Za-z0-9._-]+$/.test(name)) continue;
        written.push(await this.writeText(`plugin-logs/${name}`, await adb.runAsCat(appId, `${PLUGIN_LOG_DIR}/${name}`)));
      }
      return undefined;
    });
    const office = this.options.backOffice?.();
    if (office) {
      await item('backoffice.log', () => this.writeText('backoffice.log', office.logText()));
      await item('records.json', () => this.writeJson('records.json', office.records()));
      await item('premise.json', () => this.writeJson('premise.json', office.premiseRecords()));
    }
    await item('screenshot.png', async () => {
      const file = this.path('screenshot.png');
      await adb.screenshot(file);
      return file;
    });
    if (this.options.bugreport) {
      await item('bugreport.zip', async () => {
        const file = this.path('bugreport.zip');
        await adb.bugreport(file);
        return file;
      });
    }
    if (errors.length > 0) {
      try {
        written.push(await this.writeText('collect-errors.txt', `${errors.join('\n')}\n`));
      } catch {
        // nothing else to do
      }
    }
    return written;
  }
}
