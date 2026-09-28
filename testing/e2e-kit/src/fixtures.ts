// SCAFFOLD data (fixed) + functions of Unit 7 (e2e-kit). Shared test data of all suites, so scenarios in different
// files agree on places, routes and configs. docs/e2e/architecture.md §8.
import type { LatLon, Premise } from './types.ts';

/** Named places (Riyadh, like the docs). */
export const PLACES = {
  /** centre of the HQ premise */
  hq: { lat: 24.7136, lon: 46.6753 },
  /** 400 m east of the HQ centre: outside the 150 m premise, inside no geofence */
  hqEast400m: { lat: 24.7136, lon: 46.67926 },
  /** 1500 m south of the HQ centre */
  hqSouth1500m: { lat: 24.70011, lon: 46.6753 },
  /** 1500 m north of the HQ centre */
  hqNorth1500m: { lat: 24.72709, lon: 46.6753 },
} as const satisfies Record<string, LatLon>;

/** Premises used by the field-force suite (and circular geofences of the plugin suite). */
export const PREMISES = {
  hq: { id: 'hq', name: 'HQ', latitude: 24.7136, longitude: 46.6753, radius: 150 },
} as const satisfies Record<string, Premise>;

/** Routes for `Adb.playRoute` (lengths computed with a 6371008.8 m sphere, like haversineMeters). */
export const ROUTES = {
  /** closed rectangle 1000 m x 500 m, 1.5 km north-west of HQ: 3000 m */
  cityLoop3km: [
    { lat: 24.72, lon: 46.66 },
    { lat: 24.72, lon: 46.6699 },
    { lat: 24.724497, lon: 46.6699 },
    { lat: 24.724497, lon: 46.659999 },
    { lat: 24.72, lon: 46.66 },
  ],
  /** straight north from 1500 m south of HQ to the HQ centre: 1500 m, ends inside the premise */
  approachHq: [PLACES.hqSouth1500m, PLACES.hq],
  /** straight north from the HQ centre to 1500 m north: 1500 m, leaves the premise */
  leaveHq: [PLACES.hq, PLACES.hqNorth1500m],
} as const satisfies Record<string, readonly LatLon[]>;

/** Heartbeat interval of the e2e configs (production field-force preset: 180/300). */
export const TEST_HEARTBEAT = { minInterval: 60, maxInterval: 120 } as const;

/** `http.syncInterval` of the e2e configs (production field-force preset: 300). */
export const TEST_SYNC_INTERVAL_S = 120;

export interface PluginTestConfigOptions {
  /** records endpoint as the emulator sees it, e.g. office.url('/locations') */
  url: string;
  heartbeat?: { minInterval: number; maxInterval: number };
  /** default 0 (upload every record at once) */
  syncInterval?: number;
  /** default true */
  startOnBoot?: boolean;
  /** default false */
  stopOnTerminate?: boolean;
  /** default 'auto' */
  locationProvider?: 'auto' | 'gms' | 'hms' | 'android';
  /** JWT against the back office: `authorization.refreshUrl` = <url origin>/auth/refresh (default false) */
  jwt?: boolean;
  /** deep-merged last (arrays and maps replaced), e.g. { geolocation: { stopAfterElapsedMinutes: 2 } } */
  patch?: Record<string, unknown>;
}

/**
 * Config keys whose values are free-form maps of the app (not config sections): [deepMerge] replaces them as a whole,
 * so a patch sets exactly the given map (e.g. `http.params`), like it replaces arrays.
 */
export const REPLACED_MAP_KEYS: readonly string[] = [
  'http.params',
  'http.headers',
  'http.authorization.refreshPayload',
  'http.authorization.refreshHeaders',
  'persistence.extras',
];

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value) && Object.getPrototypeOf(value) === Object.prototype;
}

/**
 * Returns [base] with [patch] merged in: plain objects are merged key by key (recursively); arrays, the maps of
 * [REPLACED_MAP_KEYS] and every other value in [patch] replace the base value; `undefined` values in [patch] are
 * ignored. Neither argument is modified.
 */
export function deepMerge(base: Record<string, unknown>, patch: Record<string, unknown>, path = ''): Record<string, unknown> {
  const out: Record<string, unknown> = { ...base };
  for (const [key, value] of Object.entries(patch)) {
    if (value === undefined) continue;
    const keyPath = path ? `${path}.${key}` : key;
    const current = out[key];
    if (isPlainObject(value) && isPlainObject(current) && !REPLACED_MAP_KEYS.includes(keyPath)) {
      out[key] = deepMerge(current, value, keyPath);
    } else {
      out[key] = isPlainObject(value) ? deepMerge({}, value, keyPath) : value;
    }
  }
  return out;
}

/**
 * The plugin `Config` of the plugin suite (docs/e2e/architecture.md §8): `logger.logLevel 'debug'`; `heartbeat
 * {enabled: true, ...TEST_HEARTBEAT}` (or [PluginTestConfigOptions.heartbeat]); `geolocation {desiredAccuracy 'high',
 * distanceFilter 10, stopTimeout 1, stationaryRadius 25}` (stationary one minute after the last movement); `http {url,
 * autoSync: true, syncInterval: 0, batchSync: false, params: {e2e: true}}`; `app {startOnBoot: true, stopOnTerminate:
 * false}`; `locationProvider 'auto'`; with `jwt`, `http.authorization {accessToken: 'e2e-initial', refreshToken:
 * 'e2e-refresh', refreshUrl: <url origin>/auth/refresh, refreshPayload: {refresh_token: '{refreshToken}'}}`; then
 * [PluginTestConfigOptions.patch] deep-merged last (see [deepMerge]). `persistence.extras {scenario: <id>}` is added
 * when called through ScenarioContext.testConfig.
 */
export function pluginTestConfig(options: PluginTestConfigOptions): Record<string, unknown> {
  const http: Record<string, unknown> = {
    url: options.url,
    autoSync: true,
    syncInterval: options.syncInterval ?? 0,
    batchSync: false,
    params: { e2e: true },
  };
  if (options.jwt) {
    http['authorization'] = {
      accessToken: 'e2e-initial',
      refreshToken: 'e2e-refresh',
      refreshUrl: `${new URL(options.url).origin}/auth/refresh`,
      refreshPayload: { refresh_token: '{refreshToken}' },
    };
  }
  const config: Record<string, unknown> = {
    logger: { logLevel: 'debug' },
    heartbeat: { enabled: true, ...(options.heartbeat ?? TEST_HEARTBEAT) },
    geolocation: { desiredAccuracy: 'high', distanceFilter: 10, stopTimeout: 1, stationaryRadius: 25 },
    http,
    app: { startOnBoot: options.startOnBoot ?? true, stopOnTerminate: options.stopOnTerminate ?? false },
    locationProvider: options.locationProvider ?? 'auto',
  };
  return options.patch ? deepMerge(config, options.patch) : config;
}

// ---- field-force app test overrides (docs/e2e/architecture.md §10, contract with Unit 12)

/** localStorage key the field-force app reads at every launch. */
export const FIELD_FORCE_OVERRIDES_KEY = 'ff.e2e.overrides';

export interface FieldForceOverrides {
  /** local stop time 'HH:MM' (default '02:00') */
  stopAt?: string;
  heartbeat?: { minInterval: number; maxInterval: number };
  /** seconds (production 300) */
  syncInterval?: number;
  /** backend base URL (default window.FF_ENV.backendUrl) */
  backendUrl?: string;
  /** premise to monitor after the auto start; null = none (default none) */
  premise?: Premise | null;
  /** default true */
  autoStart?: boolean;
  /** deep-merged into the plugin config last */
  configPatch?: Record<string, unknown>;
}

/** The overrides every field-force scenario starts from. */
export const FIELD_FORCE_TEST_OVERRIDES: FieldForceOverrides = {
  heartbeat: TEST_HEARTBEAT,
  syncInterval: TEST_SYNC_INTERVAL_S,
};
