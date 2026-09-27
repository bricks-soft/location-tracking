// scenario() registration, requirements, dry run and the per-scenario wiring (Unit 7): device detection once per
// process, the crash scanner, artifacts on failure, and the shared mock back office.
import { createWriteStream, mkdirSync, type WriteStream } from 'node:fs';
import { basename } from 'node:path';
import { after, test, type TestContext } from 'node:test';
import { Adb } from './adb.ts';
import { AppUnderTest } from './app.ts';
import { Artifacts } from './artifacts.ts';
import { MockBackOffice } from './backoffice.ts';
import { catalogueEntry } from './catalogue.ts';
import type { E2eCommands } from './commands.ts';
import { CrashScanner } from './crash.ts';
import { detectDevice, type DeviceProfile } from './device.ts';
import { appIdFor, readEnv, type E2eEnv } from './env.ts';
import { deepMerge, pluginTestConfig, type PluginTestConfigOptions } from './fixtures.ts';

export interface ScenarioRequirements {
  /** needs `adb root` (userdebug image): kill -9, setting the clock */
  root?: boolean;
  /** API level: a number is the minimum */
  api?: number | { min?: number; max?: number };
  /** true: needs Google Play services; false: needs an image without them (P-P08) */
  gms?: boolean;
  /** takes long (deep-Doze spacing, 02:00 waits): runs only with E2E_INCLUDE_LONG=1 */
  long?: boolean;
}

export interface ScenarioOptions {
  /** default 10 minutes */
  timeoutMs?: number;
  requires?: ScenarioRequirements;
  /** skip the automatic "no crash of the app since the scenario started" check (default false) */
  allowCrash?: boolean;
}

/** What a scenario body receives. Device-facing members are created per scenario; the back office is shared. */
export interface ScenarioContext {
  readonly id: string;
  readonly title: string;
  readonly env: E2eEnv;
  /** app under test: E2E_APP_ID, else P-* = plugin example, F-* = field-force */
  readonly appId: string;
  readonly adb: Adb;
  readonly app: AppUnderTest;
  /** = app.commands */
  readonly commands: E2eCommands;
  readonly device: DeviceProfile;
  /** marked when the scenario started */
  readonly crashes: CrashScanner;
  /** `<E2E_ARTIFACTS_DIR>/<id>`, collected automatically when the scenario fails */
  readonly artifacts: Artifacts;
  readonly t: TestContext;
  /** aborted when the scenario times out (the kit's own timer at `timeoutMs`) or node:test cancels the test */
  readonly signal: AbortSignal;
  /**
   * Registers [fn] to run when the scenario ends: after the body, the crash check and artifact collection, before the
   * next scenario starts, also after a failure or a timeout. Teardowns run in reverse registration order; a failing
   * teardown fails a scenario that otherwise passed (and is only logged when the scenario already failed).
   */
  onTeardown(fn: () => Promise<void> | void): void;
  /** The test process's shared back office on E2E_BACKEND_PORT: started on first use, reset at every scenario start. */
  backOffice(): Promise<MockBackOffice>;
  /** pluginTestConfig() with `url` defaulting to the back office's `/locations` and `persistence.extras.scenario = id`. */
  testConfig(options?: Partial<PluginTestConfigOptions>): Promise<Record<string, unknown>>;
  /** A timestamped diagnostic line in the test report. */
  log(message: string): void;
}

export type ScenarioFn = (ctx: ScenarioContext) => Promise<void>;

export interface RegisteredScenario {
  id: string;
  title: string;
  options: ScenarioOptions;
}

export const DEFAULT_SCENARIO_TIMEOUT_MS = 10 * 60_000;

/**
 * Time node:test allows after the scenario's own timeout for artifact collection and teardowns (a bugreport can take
 * up to 15 minutes, so E2E_BUGREPORT=1 gets more).
 */
export function teardownGraceMs(env: E2eEnv): number {
  return env.bugreport ? 20 * 60_000 : 3 * 60_000;
}

/** Thrown into a scenario that exceeds its `timeoutMs`. */
export class ScenarioTimeoutError extends Error {
  constructor(id: string, timeoutMs: number) {
    super(`scenario ${id} timed out after ${Math.round(timeoutMs / 1000)} s`);
    this.name = 'ScenarioTimeoutError';
  }
}

const ID_PATTERN = /^(P-[LHP]\d{2}|F-\d{2})$/;
const registered: RegisteredScenario[] = [];

/** Scenarios registered in this process, in registration order. */
export function registeredScenarios(): readonly RegisteredScenario[] {
  return registered;
}

/** 'root, api>=31, gms, long' or '-' */
export function describeRequirements(requires: ScenarioRequirements | undefined): string {
  if (!requires) return '-';
  const parts: string[] = [];
  if (requires.root) parts.push('root');
  if (typeof requires.api === 'number') parts.push(`api>=${requires.api}`);
  else if (requires.api) {
    if (requires.api.min !== undefined) parts.push(`api>=${requires.api.min}`);
    if (requires.api.max !== undefined) parts.push(`api<=${requires.api.max}`);
  }
  if (requires.gms === true) parts.push('gms');
  if (requires.gms === false) parts.push('no-gms');
  if (requires.long) parts.push('long');
  return parts.length > 0 ? parts.join(', ') : '-';
}

/** Why [device] cannot run a scenario with [requires], or undefined when it can. `long` is checked separately. */
export function unmetRequirement(requires: ScenarioRequirements | undefined, device: DeviceProfile): string | undefined {
  if (!requires) return undefined;
  if (requires.root && !device.root) return 'needs adb root (a userdebug/"Google APIs" emulator image)';
  const min = typeof requires.api === 'number' ? requires.api : requires.api?.min;
  const max = typeof requires.api === 'number' ? undefined : requires.api?.max;
  if (min !== undefined && device.api < min) return `needs API >= ${min} (device: ${device.api})`;
  if (max !== undefined && device.api > max) return `needs API <= ${max} (device: ${device.api})`;
  if (requires.gms === true && !device.gms) return 'needs Google Play services';
  if (requires.gms === false && device.gms) return 'needs an image without Google Play services';
  return undefined;
}

// ---- process-wide shared state (node --test runs each file in its own process)

let sharedAdb: Adb | undefined;
let sharedDevice: Promise<DeviceProfile> | undefined;
let deviceAttempts = 0;
/** A failed device detection is retried by the next scenario, at most this many attempts per test process. */
const MAX_DEVICE_ATTEMPTS = 2;
let runLog: WriteStream | undefined;
let sharedOffice: MockBackOffice | undefined;
let officeStarting: Promise<MockBackOffice> | undefined;
let afterHookInstalled = false;
/** id of the scenario that is running (prefix of the run-wide back office log lines) */
let currentScenarioId: string | undefined;

/** Path of the run-wide back office log: every request of every scenario of the run, appended by each test file. */
export function runBackOfficeLogPath(env: E2eEnv): string {
  return `${env.artifactsDir}/_run/backoffice.log`;
}

/** Appends lines to [runBackOfficeLogPath] through one buffered stream; a failure to write never fails a scenario. */
function runLogSink(env: E2eEnv): (line: string) => void {
  return (line) => {
    try {
      if (!runLog) {
        mkdirSync(`${env.artifactsDir}/_run`, { recursive: true });
        runLog = createWriteStream(runBackOfficeLogPath(env), { flags: 'a' });
        runLog.on('error', () => {
          runLog = undefined;
        });
      }
      runLog.write(`[${currentScenarioId ?? '-'}] ${line}\n`);
    } catch {
      // the log is a convenience; the per-scenario backoffice.log artifact does not depend on it
    }
  };
}

function adbFor(env: E2eEnv): Adb {
  sharedAdb ??= new Adb({ serial: env.serial, adbPath: env.adbPath });
  return sharedAdb;
}

function deviceFor(adb: Adb): Promise<DeviceProfile> {
  if (!sharedDevice) {
    deviceAttempts += 1;
    const attempt = detectDevice(adb);
    sharedDevice = attempt;
    attempt.catch(() => {
      if (deviceAttempts < MAX_DEVICE_ATTEMPTS && sharedDevice === attempt) sharedDevice = undefined;
    });
  }
  return sharedDevice;
}

function backOfficeFor(env: E2eEnv): Promise<MockBackOffice> {
  if (!officeStarting) {
    const starting = (async () => {
      const log = runLogSink(env);
      const office = new MockBackOffice({ port: env.backendPort, log });
      await office.start();
      log(`# ${new Date().toISOString()} mock back office started on port ${office.port} by ${basename(process.argv[1] ?? '?')} (pid ${process.pid})`);
      sharedOffice = office;
      return office;
    })();
    officeStarting = starting;
    // A failed start (e.g. the port is briefly taken) is retried by the next call.
    starting.catch(() => {
      if (officeStarting === starting) officeStarting = undefined;
    });
  }
  return officeStarting;
}

function installAfterHook(): void {
  if (afterHookInstalled) return;
  afterHookInstalled = true;
  after(async () => {
    if (sharedOffice) await sharedOffice.stop();
    const log = runLog;
    runLog = undefined;
    if (log) await new Promise<void>((resolve) => log.end(() => resolve()));
  });
}

/**
 * Registers one catalogue scenario as a node:test test named `<id> <title>`.
 *
 * - [id] must be a P-* or F-* id of the catalogue (docs/e2e/architecture.md §9); duplicates in one process throw.
 * - `E2E_DRY_RUN=1`: prints `DRY-RUN <id> | <title> | requires: ... | timeout ...` and registers a skipped test; no
 *   device, no back office.
 * - Otherwise: skips `long` scenarios unless E2E_INCLUDE_LONG=1 and scenarios whose requirements the device does not
 *   meet; resets the shared back office; marks the crash scanner; runs [fn] with the kit's own timer at `timeoutMs`
 *   (node:test's timeout is `timeoutMs` + [teardownGraceMs], so artifacts and teardowns still run after a timeout); then
 *   asserts no app crash (unless `allowCrash`). On failure it collects artifacts and rethrows. Teardowns
 *   ([ScenarioContext.onTeardown]) and closing the WebView connection always run before the test ends.
 */
export function scenario(id: string, title: string, fn: ScenarioFn, options: ScenarioOptions = {}): void {
  if (!ID_PATTERN.test(id) || !catalogueEntry(id)) {
    throw new Error(`unknown scenario id '${id}': use an automated id from docs/e2e/architecture.md §9`);
  }
  if (registered.some((s) => s.id === id)) throw new Error(`scenario ${id} is registered twice`);
  registered.push({ id, title, options });

  const env = readEnv();
  const timeoutMs = options.timeoutMs ?? DEFAULT_SCENARIO_TIMEOUT_MS;
  const name = `${id} ${title}`;
  if (env.dryRun) {
    const requires = describeRequirements(options.requires);
    console.log(`DRY-RUN ${id} | ${title} | requires: ${requires} | timeout ${Math.round(timeoutMs / 1000)}s`);
    test(name, { skip: 'E2E_DRY_RUN' }, () => {});
    return;
  }
  installAfterHook();
  test(name, { timeout: timeoutMs + teardownGraceMs(env) }, (t) => runScenario(t, id, title, fn, options, env, timeoutMs));
}

async function runScenario(
  t: TestContext,
  id: string,
  title: string,
  fn: ScenarioFn,
  options: ScenarioOptions,
  env: E2eEnv,
  timeoutMs: number,
): Promise<void> {
  const requires = options.requires;
  if (requires?.long && !env.includeLong) {
    t.skip('long scenario: set E2E_INCLUDE_LONG=1');
    return;
  }
  const adb = adbFor(env);
  const device = await deviceFor(adb);
  const unmet = unmetRequirement(requires, device);
  if (unmet) {
    t.skip(unmet);
    return;
  }

  const appId = appIdFor(id, env);
  const app = new AppUnderTest(adb, appId, env);
  const crashes = new CrashScanner(adb, appId);
  const artifacts = new Artifacts({
    dir: `${env.artifactsDir}/${id}`,
    adb,
    appId,
    backOffice: () => sharedOffice,
    bugreport: env.bugreport,
  });
  if (sharedOffice) sharedOffice.reset();
  currentScenarioId = id;
  await crashes.mark();

  const started = Date.now();
  const timeoutController = new AbortController();
  const teardowns: Array<() => Promise<void> | void> = [];
  const ctx: ScenarioContext = {
    id,
    title,
    env,
    appId,
    adb,
    app,
    commands: app.commands,
    device,
    crashes,
    artifacts,
    t,
    signal: AbortSignal.any([t.signal, timeoutController.signal]),
    onTeardown: (teardown) => {
      teardowns.push(teardown);
    },
    backOffice: () => backOfficeFor(env),
    testConfig: async (config) => {
      const url = config?.url ?? (await backOfficeFor(env)).url('/locations');
      const merged = pluginTestConfig({ ...config, url });
      // persistence.extras.scenario = id, unless the patch sets that key itself.
      const persistence = (merged['persistence'] ?? {}) as Record<string, unknown>;
      const extras = (persistence['extras'] ?? {}) as Record<string, unknown>;
      return deepMerge(merged, { persistence: { ...persistence, extras: { scenario: id, ...extras } } });
    },
    log: (message) => t.diagnostic(`[+${((Date.now() - started) / 1000).toFixed(1)}s] ${message}`),
  };

  let failure: unknown;
  let failed = false;
  let timer: NodeJS.Timeout | undefined;
  try {
    const body = fn(ctx);
    // The body keeps running after a timeout (it cannot be cancelled); its late rejection must not be unhandled.
    body.catch(() => {});
    const timeout = new Promise<never>((_, reject) => {
      timer = setTimeout(() => {
        const error = new ScenarioTimeoutError(id, timeoutMs);
        timeoutController.abort(error);
        reject(error);
      }, timeoutMs);
    });
    await Promise.race([body, timeout]);
    clearTimeout(timer);
    if (!options.allowCrash) await crashes.assertNoCrash();
  } catch (error) {
    clearTimeout(timer);
    failure = error;
    failed = true;
    try {
      await artifacts.collect(error instanceof Error ? error.message : String(error));
      t.diagnostic(`artifacts: ${artifacts.dir}`);
    } catch (collectError) {
      t.diagnostic(`artifact collection failed: ${String(collectError)}`);
    }
  }
  currentScenarioId = undefined;
  for (const teardown of teardowns.reverse()) {
    try {
      await teardown();
    } catch (error) {
      t.diagnostic(`teardown failed: ${error instanceof Error ? error.message : String(error)}`);
      if (!failed) {
        failure = error;
        failed = true;
      }
    }
  }
  // An open WebView connection would keep the test process alive after the last scenario.
  await app.dispose();
  if (failed) throw failure;
}
