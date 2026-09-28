// SCAFFOLD (working): environment of an e2e run. Owned by Unit 7 (e2e-kit) from round 2 on.

/** Application ids of the two apps under test. */
export const APP_IDS = {
  /** The plugin's example app (`example/`), plugin suite `e2e/plugin`. */
  plugin: 'com.brickssoft.locationtracking.example',
  /** The field-force example app (`examples/field-force/`), suite `examples/field-force/e2e`. */
  fieldForce: 'com.brickssoft.fieldforce.example',
} as const;

/** Where the emulator reaches the host (the mock back office runs on the host). */
export const EMULATOR_HOST = '10.0.2.2';

export const DEFAULT_BACKEND_PORT = 8787;

export interface E2eEnv {
  /** `E2E_SERIAL`: adb serial; undefined = the only connected device. */
  serial: string | undefined;
  /** `E2E_APP_ID`: overrides the app id derived from the scenario id (P-* plugin example, F-* field-force). */
  appIdOverride: string | undefined;
  /** `E2E_APK`: APK of the app under test, used by (re)install scenarios; CI installs it before the suites. */
  apk: string | undefined;
  /** `E2E_BACKEND_PORT`: port of the mock back office on the host (default 8787). */
  backendPort: number;
  /** `E2E_INCLUDE_LONG=1`: also run scenarios marked `requires.long` (deep-Doze spacing and similar). */
  includeLong: boolean;
  /** `E2E_DRY_RUN=1`: register and list scenarios, run nothing, need no device. */
  dryRun: boolean;
  /** `E2E_ARTIFACTS_DIR`: failure artifacts (default `<cwd>/e2e-artifacts`). */
  artifactsDir: string;
  /** `E2E_ADB`: adb binary (default `$ANDROID_HOME/platform-tools/adb`, else `adb` on PATH). */
  adbPath: string;
  /** `E2E_BUGREPORT=1`: also collect a full bugreport on failure (slow, large). */
  bugreport: boolean;
}

function flag(value: string | undefined): boolean {
  return value === '1' || value === 'true' || value === 'yes';
}

function nonEmpty(value: string | undefined): string | undefined {
  return value !== undefined && value.trim() !== '' ? value.trim() : undefined;
}

/** Reads the e2e environment variables (see docs/e2e/architecture.md §8). */
export function readEnv(env: NodeJS.ProcessEnv = process.env): E2eEnv {
  const port = Number.parseInt(env['E2E_BACKEND_PORT'] ?? '', 10);
  const androidHome = nonEmpty(env['ANDROID_HOME']) ?? nonEmpty(env['ANDROID_SDK_ROOT']);
  return {
    serial: nonEmpty(env['E2E_SERIAL']),
    appIdOverride: nonEmpty(env['E2E_APP_ID']),
    apk: nonEmpty(env['E2E_APK']),
    backendPort: Number.isFinite(port) && port > 0 ? port : DEFAULT_BACKEND_PORT,
    includeLong: flag(env['E2E_INCLUDE_LONG']),
    dryRun: flag(env['E2E_DRY_RUN']),
    artifactsDir: nonEmpty(env['E2E_ARTIFACTS_DIR']) ?? `${process.cwd()}/e2e-artifacts`,
    adbPath: nonEmpty(env['E2E_ADB']) ?? (androidHome ? `${androidHome}/platform-tools/adb` : 'adb'),
    bugreport: flag(env['E2E_BUGREPORT']),
  };
}

/** The app a scenario drives: `E2E_APP_ID`, else by id prefix (`P-` plugin example, `F-` field-force). */
export function appIdFor(scenarioId: string, env: E2eEnv): string {
  if (env.appIdOverride) return env.appIdOverride;
  return scenarioId.startsWith('F-') ? APP_IDS.fieldForce : APP_IDS.plugin;
}
