// Owned by Unit 1 (TS wrapper + web).
import { WebPlugin } from '@capacitor/core';

import type {
  BatteryOptimizationStatus,
  CallbackID,
  Config,
  CurrentPositionOptions,
  DesiredAccuracy,
  DeviceInfo,
  Geofence,
  HeartbeatStatus,
  InsertLocationInput,
  Location,
  LocationTrackingEventMap,
  LocationTrackingPlugin,
  LogLevel,
  LogQuery,
  PermissionStatus,
  PermissionType,
  PowerManagerInfo,
  ProviderState,
  ReadyOptions,
  Sensors,
  State,
  WatchPositionCallback,
  WatchPositionOptions,
} from './definitions';
import { cloneJson, defaultConfig, isPlainObject, mergeConfig, webState } from './web/config';
import { webDeviceInfo } from './web/device';
import { positionErrorInfo, webError } from './web/errors';
import {
  browserGeolocation,
  probeLocationPermission,
  queryLocationPermission,
  sampleBestPosition,
} from './web/geolocation';
import { toLocation } from './web/location';

const ALL_PERMISSIONS: PermissionType[] = ['location', 'notifications', 'activityRecognition', 'backgroundLocation'];

interface WatchEntry {
  active: boolean;
  geolocation: Geolocation;
  browserId: number;
}

function highAccuracy(accuracy: DesiredAccuracy | undefined): boolean {
  return accuracy === undefined || accuracy === 'high';
}

function nonNegative(value: unknown, fallback: number): number {
  return typeof value === 'number' && value >= 0 ? value : fallback;
}

/** The defaults plus `options.config` (optional; must be an object when given). */
function configFromDefaults(options: { config?: Config } | undefined): Config {
  const given = options ? options.config : undefined;
  if (given !== undefined && given !== null && !isPlainObject(given)) {
    throw webError('INVALID_ARGUMENT', 'config must be an object.');
  }
  return mergeConfig(defaultConfig(), given || undefined);
}

/**
 * Web implementation. It covers `getCurrentPosition`, `watchPosition` and `clearWatch` (via `navigator.geolocation`),
 * an in-memory config and state, permissions and device info. Nothing is persisted or uploaded, and tracking cannot
 * be started. Every other method rejects with `UNIMPLEMENTED`.
 *
 * Like the Android bridge, methods other than `ready`, `getState`, `checkPermissions`, `requestPermissions` and
 * `getDeviceInfo` reject with `NOT_READY` until `ready()` has resolved.
 */
export class LocationTrackingWeb extends WebPlugin implements LocationTrackingPlugin {
  private config: Config = defaultConfig();
  private isReady = false;
  private lastPosition: GeolocationPosition | null = null;
  private watchSeq = 0;
  private readonly watches = new Map<string, WatchEntry>();

  async ready(options?: ReadyOptions): Promise<State> {
    const config = configFromDefaults(options);
    // reset:false applies the given config only on the first ready(); afterwards the current config wins.
    if (!options || options.reset !== false || !this.isReady) {
      this.config = config;
    }
    this.isReady = true;
    return webState(this.config);
  }

  async setConfig(options: { config: Config }): Promise<State> {
    this.requireReady();
    if (!options || !isPlainObject(options.config)) {
      throw webError('INVALID_ARGUMENT', 'config must be an object.');
    }
    this.config = mergeConfig(this.config, options.config);
    return webState(this.config);
  }

  async reset(options?: { config?: Config }): Promise<State> {
    this.requireReady();
    this.config = configFromDefaults(options);
    return webState(this.config);
  }

  async getState(): Promise<State> {
    return webState(this.config);
  }

  async start(): Promise<State> {
    throw this.unimplemented('Not implemented on web.');
  }
  async startGeofences(): Promise<State> {
    throw this.unimplemented('Not implemented on web.');
  }
  async stop(): Promise<State> {
    throw this.unimplemented('Not implemented on web.');
  }
  async changePace(_options: { isMoving: boolean }): Promise<void> {
    throw this.unimplemented('Not implemented on web.');
  }

  /**
   * Takes `samples` fixes within `timeout` and resolves the most accurate one. With `maximumAge > 0`, a fix this
   * plugin received at most that long ago is returned right away. Like a persisted record on Android, the result is
   * also emitted as a `location` event unless `persist` is false.
   */
  async getCurrentPosition(options?: CurrentPositionOptions): Promise<Location> {
    this.requireReady();
    const geolocation = this.requireGeolocation();
    const o = options || {};
    const maximumAge = nonNegative(o.maximumAge, 0);
    const cached = this.lastPosition;
    let position: GeolocationPosition;
    if (maximumAge > 0 && cached && Date.now() - cached.timestamp <= maximumAge) {
      position = cached;
    } else {
      const samples = typeof o.samples === 'number' && o.samples >= 1 ? Math.floor(o.samples) : 3;
      position = await sampleBestPosition(geolocation, {
        samples,
        timeout: nonNegative(o.timeout, nonNegative(this.config.geolocation?.locationTimeout, 30000)),
        maximumAge,
        enableHighAccuracy: highAccuracy(o.desiredAccuracy),
      });
    }
    this.lastPosition = position;
    const location = toLocation(position, 'current_position', this.recordExtras(o.extras));
    if (o.persist !== false) {
      this.emit('location', location);
    }
    return location;
  }

  /**
   * Starts `navigator.geolocation.watchPosition` and resolves its id. The callback gets each fix as a
   * `watch_position` Location, or `(null, { code, message })` on error. `interval` is ignored: the browser decides
   * how often fixes arrive. With `persist: true` each fix is also emitted as a `location` event.
   */
  async watchPosition(options: WatchPositionOptions, callback: WatchPositionCallback): Promise<CallbackID> {
    this.requireReady();
    if (typeof callback !== 'function') {
      throw webError('INVALID_ARGUMENT', 'watchPosition requires a callback.');
    }
    const geolocation = this.requireGeolocation();
    const o = options || {};
    const callExtras = cloneJson(o.extras);
    const persist = o.persist === true;
    const entry: WatchEntry = { active: true, geolocation, browserId: 0 };
    try {
      entry.browserId = geolocation.watchPosition(
        (position) => {
          // Browsers may still deliver a queued fix after clearWatch.
          if (!entry.active) {
            return;
          }
          this.lastPosition = position;
          const location = toLocation(position, 'watch_position', this.recordExtras(callExtras));
          if (persist) {
            this.emit('location', location);
          }
          callback(location);
        },
        (error) => {
          if (entry.active) {
            callback(null, positionErrorInfo(error));
          }
        },
        { enableHighAccuracy: highAccuracy(o.desiredAccuracy), maximumAge: 0 },
      );
    } catch (e) {
      throw webError('UNAVAILABLE', e instanceof Error ? e.message : String(e));
    }
    const id = `web-watch-${++this.watchSeq}`;
    this.watches.set(id, entry);
    return id;
  }

  /** Stops a watch. Unknown ids are ignored. */
  async clearWatch(options: { id: CallbackID }): Promise<void> {
    this.requireReady();
    if (!options || typeof options.id !== 'string') {
      throw webError('INVALID_ARGUMENT', 'id must be a string.');
    }
    const entry = this.watches.get(options.id);
    if (!entry) {
      return;
    }
    this.watches.delete(options.id);
    entry.active = false;
    entry.geolocation.clearWatch(entry.browserId);
  }

  async getOdometer(): Promise<{ odometer: number }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async setOdometer(_options: { odometer: number }): Promise<{ odometer: number }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async resetOdometer(): Promise<{ odometer: number }> {
    throw this.unimplemented('Not implemented on web.');
  }

  async getLocations(_options?: { limit?: number }): Promise<{ locations: Location[] }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async getCount(): Promise<{ count: number }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async insertLocation(_options: { location: InsertLocationInput }): Promise<{ uuid: string }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async destroyLocations(): Promise<{ count: number }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async destroyLocation(_options: { uuid: string }): Promise<{ deleted: boolean }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async sync(): Promise<{ locations: Location[] }> {
    throw this.unimplemented('Not implemented on web.');
  }

  async addGeofence(_options: { geofence: Geofence }): Promise<void> {
    throw this.unimplemented('Not implemented on web.');
  }
  async addGeofences(_options: { geofences: Geofence[] }): Promise<void> {
    throw this.unimplemented('Not implemented on web.');
  }
  async removeGeofence(_options: { identifier: string }): Promise<void> {
    throw this.unimplemented('Not implemented on web.');
  }
  async removeGeofences(_options?: { identifiers?: string[] }): Promise<void> {
    throw this.unimplemented('Not implemented on web.');
  }
  async getGeofences(): Promise<{ geofences: Geofence[] }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async getGeofence(_options: { identifier: string }): Promise<{ geofence: Geofence | null }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async geofenceExists(_options: { identifier: string }): Promise<{ exists: boolean }> {
    throw this.unimplemented('Not implemented on web.');
  }

  async getHeartbeatStatus(): Promise<HeartbeatStatus> {
    throw this.unimplemented('Not implemented on web.');
  }

  async getProviderState(): Promise<ProviderState> {
    throw this.unimplemented('Not implemented on web.');
  }
  async isPowerSaveMode(): Promise<{ isPowerSaveMode: boolean }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async getBatteryOptimizationStatus(): Promise<BatteryOptimizationStatus> {
    throw this.unimplemented('Not implemented on web.');
  }
  async openBatteryOptimizationSettings(): Promise<{ opened: boolean }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async getPowerManagerInfo(): Promise<PowerManagerInfo> {
    throw this.unimplemented('Not implemented on web.');
  }
  async openPowerManagerSettings(): Promise<{ opened: boolean }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async openLocationSettings(): Promise<{ opened: boolean }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async openAppSettings(): Promise<{ opened: boolean }> {
    throw this.unimplemented('Not implemented on web.');
  }

  /** Best-effort values parsed from `navigator.userAgent`; `platform` is `'web'`. */
  async getDeviceInfo(): Promise<DeviceInfo> {
    const nav = typeof navigator !== 'undefined' && navigator ? navigator : undefined;
    return webDeviceInfo(nav && typeof nav.userAgent === 'string' ? nav.userAgent : '', nav ? nav.platform : undefined);
  }
  async getSensors(): Promise<Sensors> {
    throw this.unimplemented('Not implemented on web.');
  }

  /** `location` comes from the Permissions API (`'prompt'` when it cannot tell); the other three are `'granted'`. */
  async checkPermissions(): Promise<PermissionStatus> {
    const location = await queryLocationPermission();
    return {
      location: location || 'prompt',
      backgroundLocation: 'granted',
      activityRecognition: 'granted',
      notifications: 'granted',
    };
  }

  /** When `location` is requested and still undecided, asks for a fix so the browser prompts, then reports. */
  async requestPermissions(options?: { permissions?: PermissionType[] }): Promise<PermissionStatus> {
    const requested = options && Array.isArray(options.permissions) ? options.permissions : ALL_PERMISSIONS;
    const status = await this.checkPermissions();
    const geolocation = browserGeolocation();
    if (requested.indexOf('location') < 0 || status.location !== 'prompt' || !geolocation) {
      return status;
    }
    const answer = await probeLocationPermission(geolocation);
    // A granted probe is proof, even for a one-time grant the Permissions API still reports as 'prompt'. Otherwise
    // prefer the Permissions API: a dismissed prompt fails the probe as denied but leaves the permission at 'prompt'.
    status.location = answer === 'granted' ? answer : (await queryLocationPermission()) || answer;
    return status;
  }

  async log(_options: { level: Exclude<LogLevel, 'off'>; message: string }): Promise<void> {
    throw this.unimplemented('Not implemented on web.');
  }
  async getLog(_options?: LogQuery): Promise<{ log: string }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async destroyLog(): Promise<void> {
    throw this.unimplemented('Not implemented on web.');
  }
  async uploadLog(_options: {
    url: string;
    headers?: Record<string, string>;
    params?: Record<string, unknown>;
  }): Promise<{ success: boolean; status: number }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async emailLog(_options: { email: string; subject?: string }): Promise<void> {
    throw this.unimplemented('Not implemented on web.');
  }

  private requireReady(): void {
    if (!this.isReady) {
      throw webError('NOT_READY', 'Call ready() before using this method.');
    }
  }

  private requireGeolocation(): Geolocation {
    const geolocation = browserGeolocation();
    if (!geolocation) {
      throw webError('UNAVAILABLE', 'navigator.geolocation is not available.');
    }
    return geolocation;
  }

  /** `persistence.extras` merged with the call's extras (the call wins), like records created on Android. */
  private recordExtras(extras: Record<string, unknown> | undefined): Record<string, unknown> | undefined {
    const persisted = this.config.persistence ? this.config.persistence.extras : undefined;
    const merged: Record<string, unknown> = {};
    if (isPlainObject(persisted)) {
      Object.assign(merged, cloneJson(persisted));
    }
    if (isPlainObject(extras)) {
      Object.assign(merged, cloneJson(extras));
    }
    return Object.keys(merged).length > 0 ? merged : undefined;
  }

  /**
   * Like `notifyListeners`, but each listener gets its own copy of the payload (as on native, where every listener
   * receives deserialized data), and a throwing listener neither skips the others nor fails the calling method.
   */
  private emit<K extends keyof LocationTrackingEventMap>(eventName: K, data: LocationTrackingEventMap[K]): void {
    const listeners = this.listeners[eventName];
    if (!listeners) {
      return;
    }
    for (const listener of listeners.slice()) {
      try {
        listener(cloneJson(data));
      } catch (e) {
        console.error(`LocationTracking: "${eventName}" listener threw`, e);
      }
    }
  }
}
