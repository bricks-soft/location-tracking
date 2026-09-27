// STUB — owned by Unit 7 (e2e-kit).
import type { Adb } from './adb.ts';
import type { MockBackOffice } from './backoffice.ts';
import { notImplemented } from './util.ts';

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

/**
 * Failure artifacts of one scenario. [collect] writes (best effort, never throws): `logcat.txt` (all buffers),
 * `crash.txt`, `dumpsys-activity-services.txt`, `dumpsys-location.txt`, `dumpsys-deviceidle.txt`, `dumpsys-alarm.txt`
 * (app lines), `dumpsys-jobscheduler.txt`, `plugin-logs/` (the plugin's own log files via run-as), `backoffice.log`,
 * `records.json`, `premise.json`, `screenshot.png` and, if enabled, `bugreport.zip`.
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
    return notImplemented('Artifacts.writeText');
  }

  async writeJson(name: string, value: unknown): Promise<string> {
    return notImplemented('Artifacts.writeJson');
  }

  /** Collects everything listed above; resolves with the written paths. */
  async collect(reason: string): Promise<string[]> {
    return notImplemented('Artifacts.collect');
  }
}
