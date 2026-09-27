// Owned by Unit 1 (TS wrapper + web).
import type { AuthorizationConfig, Config, State } from '../definitions';

type JsonObject = Record<string, unknown>;

/** Never copied: assigning `__proto__` would change the target's prototype instead of adding a key. */
const PROTO_KEY = '__proto__';

/** Free-form maps. They are config values, not sections: a new value replaces the old one instead of merging. */
const MAP_VALUES = [
  'http.headers',
  'http.params',
  'persistence.extras',
  'http.authorization.refreshPayload',
  'http.authorization.refreshHeaders',
];

/** Sections whose own default is `null`. `null` resets them to `null`; setting them fills in their field defaults. */
const NULLABLE_SECTIONS = ['http.authorization'];

/**
 * Every documented `@default` of {@link Config}, mirroring the Android `Config` model.
 * Keys without a default (for example `notification.title`, which falls back to the app label) are left out.
 */
export function defaultConfig(): Config {
  return {
    geolocation: {
      desiredAccuracy: 'high',
      distanceFilter: 10,
      locationUpdateInterval: 1000,
      fastestLocationUpdateInterval: 500,
      disableElasticity: false,
      elasticityMultiplier: 1,
      stationaryRadius: 25,
      stopTimeout: 5,
      stopAfterElapsedMinutes: 0,
      stopOnStationary: false,
      locationTimeout: 30000,
      filter: {
        useKalman: false,
        trackingAccuracyThreshold: 100,
        maxImpliedSpeed: 80,
        odometerAccuracyThreshold: 20,
        allowIdenticalLocations: false,
        rejectMockLocations: false,
      },
    },
    activity: {
      disableMotionActivityUpdates: false,
      activityRecognitionInterval: 10000,
      minimumActivityRecognitionConfidence: 75,
      motionTriggerDelay: 0,
      disableStopDetection: false,
    },
    heartbeat: {
      enabled: true,
      minInterval: 180,
      maxInterval: 300,
    },
    http: {
      url: null,
      method: 'POST',
      headers: {},
      params: {},
      autoSync: true,
      autoSyncThreshold: 0,
      batchSync: false,
      maxBatchSize: 100,
      disableAutoSyncOnCellular: false,
      rootProperty: 'location',
      locationTemplate: null,
      geofenceTemplate: null,
      timeout: 60000,
      authorization: null,
    },
    persistence: {
      maxDaysToPersist: 7,
      maxRecordsToPersist: -1,
      extras: {},
    },
    app: {
      stopOnTerminate: true,
      startOnBoot: false,
    },
    notification: {
      text: 'Location tracking is active',
      smallIcon: 'drawable/lt_ic_notification',
      priority: 'default',
      channelId: 'location_tracking',
      channelName: 'Location tracking',
      actions: [],
    },
    geofence: {
      initialTriggerEntry: true,
    },
    logger: {
      logLevel: 'info',
      logMaxDays: 3,
    },
    backgroundPermissionRationale: {},
    locationProvider: 'auto',
  };
}

/** Field defaults of `http.authorization`, applied once it is set (the section itself defaults to `null`). */
function defaultAuthorization(): AuthorizationConfig {
  return {
    strategy: 'JWT',
    refreshPayload: {},
    refreshHeaders: {},
    refreshPayloadEncoding: 'json',
    expires: -1,
  };
}

interface DefaultTrees {
  /** The real defaults (`http.authorization` is `null`). */
  values: JsonObject;
  /** The defaults with nullable sections filled in, used to find sections and the defaults of their fields. */
  sections: JsonObject;
}

function defaultTrees(): DefaultTrees {
  const sections = defaultConfig() as unknown as JsonObject;
  (sections.http as JsonObject).authorization = defaultAuthorization();
  return { values: defaultConfig() as unknown as JsonObject, sections };
}

export function isPlainObject(value: unknown): value is JsonObject {
  return Object.prototype.toString.call(value) === '[object Object]';
}

/** Deep copy of JSON-like data (plain objects, arrays and primitives). */
export function cloneJson<T>(value: T): T {
  if (Array.isArray(value)) {
    return value.map((item) => cloneJson(item)) as unknown as T;
  }
  if (isPlainObject(value)) {
    const copy: JsonObject = {};
    for (const key of Object.keys(value)) {
      if (key !== PROTO_KEY) {
        copy[key] = cloneJson(value[key]);
      }
    }
    return copy as T;
  }
  return value;
}

function lookup(tree: JsonObject, path: string[]): unknown {
  let node: unknown = tree;
  for (const key of path) {
    if (!isPlainObject(node)) {
      return undefined;
    }
    node = node[key];
  }
  return node;
}

function hasValue(patch: JsonObject): boolean {
  return Object.keys(patch).some((key) => patch[key] !== undefined && patch[key] !== null);
}

function mergeInto(target: JsonObject, patch: JsonObject, path: string[], trees: DefaultTrees): void {
  for (const key of Object.keys(patch)) {
    const value = patch[key];
    if (value === undefined || key === PROTO_KEY) {
      continue;
    }
    const childPath = path.concat(key);
    const dotted = childPath.join('.');
    const sectionDefaults = lookup(trees.sections, childPath);
    const isSection = isPlainObject(sectionDefaults) && MAP_VALUES.indexOf(dotted) < 0;

    if (value === null) {
      // null resets the key to its default (fields of a null section use the section's field defaults);
      // keys without a default are removed.
      const own = lookup(trees.values, childPath);
      const reset = own !== undefined ? own : sectionDefaults;
      if (reset === undefined) {
        delete target[key];
      } else {
        target[key] = cloneJson(reset);
      }
    } else if (isSection) {
      // A section only accepts an object; anything else is ignored so the section stays fully populated.
      const current = target[key];
      // A null section is only switched on by a patch that sets something.
      const staysNull = NULLABLE_SECTIONS.indexOf(dotted) >= 0 && current === null && !hasValue(value as JsonObject);
      if (isPlainObject(value) && !staysNull) {
        const base = isPlainObject(current) ? current : (cloneJson(sectionDefaults) as JsonObject);
        mergeInto(base, value as JsonObject, childPath, trees);
        target[key] = base;
      }
    } else {
      // Arrays, maps and scalar values are replaced.
      target[key] = cloneJson(value);
    }
  }
}

/** The same clamps the Android config validator applies. */
function clamp(config: Config): void {
  const heartbeat = config.heartbeat;
  if (heartbeat) {
    if (typeof heartbeat.minInterval === 'number' && heartbeat.minInterval < 60) {
      heartbeat.minInterval = 60;
    }
    if (
      typeof heartbeat.minInterval === 'number' &&
      typeof heartbeat.maxInterval === 'number' &&
      heartbeat.maxInterval < heartbeat.minInterval
    ) {
      heartbeat.maxInterval = heartbeat.minInterval;
    }
  }
  const activity = config.activity;
  if (activity && typeof activity.minimumActivityRecognitionConfidence === 'number') {
    activity.minimumActivityRecognitionConfidence = Math.min(
      100,
      Math.max(0, activity.minimumActivityRecognitionConfidence),
    );
  }
  const http = config.http;
  if (http && typeof http.maxBatchSize === 'number' && http.maxBatchSize < 1) {
    http.maxBatchSize = 1;
  }
  const notification = config.notification;
  if (notification && Array.isArray(notification.actions) && notification.actions.length > 3) {
    notification.actions = notification.actions.slice(0, 3);
  }
}

/**
 * Returns `base` deep-merged with `patch`, without changing either.
 * - Sections are merged key by key; arrays, maps (`headers`, `params`, `extras`, ...) and scalars are replaced.
 * - `null` resets a key to its default (`http.authorization` resets to `null`); `undefined` is ignored.
 */
export function mergeConfig(base: Config, patch: Config | undefined): Config {
  const result = cloneJson(base) as unknown as JsonObject;
  if (patch) {
    mergeInto(result, patch as unknown as JsonObject, [], defaultTrees());
  }
  const config = result as unknown as Config;
  clamp(config);
  return config;
}

/** The web `State`: tracking is never enabled on web, and nothing is recorded or persisted. */
export function webState(config: Config): State {
  return {
    enabled: false,
    trackingMode: 'location',
    isMoving: false,
    odometer: 0,
    backend: 'web',
    lastRecordAt: null,
    config: cloneJson(config),
  };
}
