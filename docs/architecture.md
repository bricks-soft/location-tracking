# Architecture and contracts: `@bricks-soft/capacitor-location-tracking`

This document is the contract between the scaffold and all work units. The scaffold owns it. Units must not change it; if a contract is insufficient, describe a "Contract change request" in the unit's final report.

**Status: as built (0.1.0).** All units are merged, and this document was updated after integration to describe the contracts as implemented, including the accepted contract change requests (`TrackingEngine.onServiceStartFailed` and the `service_start_failed` reason, geofence re-registration on provider changes, the web stub's extra methods, the R8 keep rules and the docgen config). User-facing behavior is documented in `README.md` and `docs/*.md`; this document keeps only what the components promise each other.

**Round 2** (field-force audit: companion native API, stationary GPS-off mode, `http.syncInterval`, heartbeat metadata, AVD end-to-end tests) is specified in [docs/e2e/architecture.md](e2e/architecture.md), which overrides this document where they differ.

## Product decisions

- This is a clean-room implementation with the feature set of `transistorsoft/capacitor-background-geolocation`. Its native engine is closed-source and commercial, and **none of it is copied**.
- It has a new, smaller API:
  - npm package `@bricks-soft/capacitor-location-tracking`;
  - JS plugin object `LocationTracking` (`registerPlugin('LocationTracking')`);
  - Android package `com.brickssoft.locationtracking` (Kotlin).
- iOS is deferred to a later phase. Web is a development stub: `getCurrentPosition`, `watchPosition` and `clearWatch` (using `navigator.geolocation`), an in-memory `ready`/`setConfig`/`reset`/`getState`, `checkPermissions`, `requestPermissions` and `getDeviceInfo` (see §1).
- **GMS/HMS selection.** There are three location backends: GMS (Google Play services), HMS (Huawei Location Kit) and the plain Android `LocationManager`.
  - The plugin compiles against both SDKs with `compileOnly`.
  - The app chooses which SDKs are packaged with the Gradle property `locationTracking.providers=gms,hms` (default `gms`).
  - Config `locationProvider: 'auto'|'gms'|'hms'|'android'` selects one at runtime. `auto` means: GMS if its class is present and `GoogleApiAvailability` reports SUCCESS; otherwise HMS if its class is present and `HuaweiApiAvailability` reports SUCCESS; otherwise Android. An explicit `gms`/`hms` that is not packaged or not available falls back to Android (logged).
  - Backend bundles are created only through reflection.
- **Heartbeat (audit):**
  - **Trigger.** While tracking is enabled, if no record has been created for `heartbeat.minInterval` (default 180 s), the plugin creates a `heartbeat` record with the last known location.
  - **Window.** It must fire within [`minInterval`, `maxInterval`] (default 180–300 s) after the last record: it is due at `minInterval`, and `maxInterval` is the deadline (later deliveries are logged as late). A record created inside the window restarts it. `insertLocation()` records do not count.
  - **Delivery.** It is POSTed to the same `http.url`, in the same record shape, immediately.
  - **Failure.** It stays queued in SQLite and is retried later. `recorded_at` and `sent_at` let the server detect late delivery.
- **Audit records**, POSTed the same way: `tracking_start`, `tracking_stop` (including reason `service_start_failed` when Android refuses the foreground service), `providerchange`.
- **Idle-mode permissions.** Only the battery-optimization exemption is used: the plugin opens the settings list with `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`. It does **not** declare `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM` or `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- **Excluded:** schedule, headless mode, debug sounds, the background-task API, more than 100 geofences, iOS code, transistor-background-fetch.

### Alarm facts (AOSP `AlarmManagerService`, main)

- If the user has exempted the app from battery optimization (`isUidPowerSaveUserExempt`), its alarms get `FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED`, so there is no idle limit. `canScheduleExactAlarms()` also returns true (`isExemptFromExactAlarmPermission`).
- Inexact allow-while-idle alarms (`setAndAllowWhileIdle`) otherwise get the compat quota: **7 per rolling hour** in deep idle, equivalent to one every 9 minutes.
- Listener-based exact alarms (`setExact(..., OnAlarmListener, Handler)`) need no permission. They work only while the process is alive, and deep idle defers them.

## Known-good versions

| Item | Version |
|---|---|
| Capacitor (`core`, `android`, `cli`) | 8.5.2 |
| AGP | 8.13.0 |
| Kotlin Gradle plugin | 2.2.20 |
| compileSdk / targetSdk | 36 / 36 |
| minSdk | 24 |
| Java | 21 |
| Gradle | 8.14.3 |
| kotlinx-coroutines | 1.10.2 |
| okhttp / mockwebserver | 4.12.0 |
| robolectric | 4.16.1 |
| mockk | 1.14.6 |
| androidx.test:core-ktx | 1.7.0 |
| androidx.core:core-ktx | 1.17.0 |
| appcompat | 1.7.1 |
| play-services-location | 21.3.0 |
| com.huawei.hms:location | 6.12.0.300 |

## 0. Global rules for every unit

- **Ownership.** You own only the files listed for your unit. You may add new files only inside your own package directories (`android/src/main/java/com/brickssoft/locationtracking/<pkg>/` and the matching `src/test/...`).
  - Never edit SCAFFOLD files: `Components.kt`, contract interfaces, models, `ModelJson.kt`, the manifest, build files, `package.json`, `definitions.ts`, test fakes under `testing/`, and this document.
  - If a contract is not enough, work around it inside your own files and list a "Contract change request" in your final report.
- **Dependencies.** Don't add Gradle or npm dependencies. Everything needed is already declared.
- **Constructors.** Keep the constructor signature that `Components` uses. You may append parameters only if they have default values (for example, injectable client factories for tests).
- **Stubs never throw.** Every stub is a neutral no-op. Keep yours that way until it is implemented.
- **Time, threads and logging.** Use `Clock` (never `System.currentTimeMillis`), `AppDispatchers` (never hard-code `Dispatchers.IO`), and `Logger` (never `android.util.Log` directly).
- **No `java.time`.** minSdk is 24 and desugaring is off; use `core/Iso8601`.
- **No GMS/HMS types outside their packages.** Never reference `com.google.*` or `com.huawei.*` types outside `provider/gms` and `provider/hms`.
- **Tests.** Test only your own classes, using the fakes in `testing/`. Never instantiate another unit's `Default*` class in tests.
- **Verification.**
  - Run `scripts/gradle-slot.sh -p android compileDebugKotlin testDebugUnitTest --tests 'com.brickssoft.locationtracking.<yourpkg>.*'`.
  - Then run the full `testDebugUnitTest` once before committing.
  - Never run `gradle build` or `lint`, because capacitor-android's lint uses `warningsAsErrors`.
  - Run `npm run build` if you touched TypeScript.
- **Per-worktree setup.** Run `npm ci --prefer-offline --no-audit --no-fund` in each worktree. Do **not** symlink `node_modules` between worktrees: `:capacitor-android` builds inside `node_modules`, so shared build directories would get corrupted.
- **Robolectric SDKs.** Use only `@Config(sdk=...)` values from {29, 30, 33, 34, 35}. They are pre-downloaded. Default is 34 (`src/test/resources/robolectric.properties`).

---

## 1. TypeScript contract: `src/definitions.ts` (SCAFFOLD, complete)

```ts
import type { PermissionState, PluginListenerHandle } from '@capacitor/core';

export type CallbackID = string;
export type DesiredAccuracy = 'high' | 'balanced' | 'low' | 'passive';
export type LocationProviderSetting = 'auto' | 'gms' | 'hms' | 'android';
export type LocationBackend = 'gms' | 'hms' | 'android' | 'web';
export type TrackingMode = 'location' | 'geofences';
export type LogLevel = 'off' | 'error' | 'warn' | 'info' | 'debug' | 'verbose';
export type ActivityType = 'still' | 'on_foot' | 'walking' | 'running' | 'on_bicycle' | 'in_vehicle' | 'unknown';
export type RecordEvent = 'location' | 'motionchange' | 'current_position' | 'watch_position'
  | 'heartbeat' | 'geofence' | 'tracking_start' | 'tracking_stop' | 'providerchange';
export type GeofenceAction = 'ENTER' | 'EXIT' | 'DWELL';
export type HttpMethod = 'POST' | 'PUT' | 'PATCH';
export type NotificationPriority = 'min' | 'low' | 'default' | 'high' | 'max';
export type PermissionType = 'location' | 'backgroundLocation' | 'activityRecognition' | 'notifications';
export type ConnectivityType = 'wifi' | 'cellular' | 'ethernet' | 'other' | 'none';
export type HeartbeatStrategy = 'exact' | 'listener_with_backup' | 'idle_paced' | 'disabled';
/** Rejection `code` of every failed promise. */
export type ErrorCode = 'NOT_READY' | 'PERMISSION_DENIED' | 'LOCATION_DISABLED' | 'TIMEOUT' | 'UNAVAILABLE'
  | 'INVALID_ARGUMENT' | 'NOT_FOUND' | 'NO_URL' | 'HTTP_ERROR' | 'NETWORK_ERROR' | 'TOO_MANY_GEOFENCES'
  | 'NO_ACTIVITY' | 'IO_ERROR' | 'UNIMPLEMENTED' | 'INTERNAL';

/* ================= CONFIG (all optional; defaults shown) ================= */
export interface LocationFilterConfig {
  /** @default false */ useKalman?: boolean;
  /** Reject fixes with accuracy worse than this (m). @default 100 */ trackingAccuracyThreshold?: number;
  /** Reject fixes implying speed above this (m/s); 0 = off. @default 80 */ maxImpliedSpeed?: number;
  /** Odometer ignores fixes worse than this (m). @default 20 */ odometerAccuracyThreshold?: number;
  /** @default false */ allowIdenticalLocations?: boolean;
  /** Drop mock fixes entirely (otherwise they are kept with mock:true). @default false */ rejectMockLocations?: boolean;
}
export interface GeolocationConfig {
  /** @default 'high' */ desiredAccuracy?: DesiredAccuracy;
  /** meters @default 10 */ distanceFilter?: number;
  /** ms @default 1000 */ locationUpdateInterval?: number;
  /** ms @default 500 */ fastestLocationUpdateInterval?: number;
  /** @default false */ disableElasticity?: boolean;
  /** @default 1 */ elasticityMultiplier?: number;
  /** meters @default 25 */ stationaryRadius?: number;
  /** minutes @default 5 */ stopTimeout?: number;
  /** minutes, 0 = off @default 0 */ stopAfterElapsedMinutes?: number;
  /** @default false */ stopOnStationary?: boolean;
  /** default getCurrentPosition timeout, ms @default 30000 */ locationTimeout?: number;
  filter?: LocationFilterConfig;
}
export interface ActivityConfig {
  /** @default false */ disableMotionActivityUpdates?: boolean;
  /** ms @default 10000 */ activityRecognitionInterval?: number;
  /** 0-100 @default 75 */ minimumActivityRecognitionConfidence?: number;
  /** ms @default 0 */ motionTriggerDelay?: number;
  /** @default false */ disableStopDetection?: boolean;
}
export interface HeartbeatConfig {
  /** @default true */ enabled?: boolean;
  /** seconds, min 60 @default 180 */ minInterval?: number;
  /** seconds, >= minInterval @default 300 */ maxInterval?: number;
}
export interface AuthorizationConfig {
  /** @default 'JWT' */ strategy?: 'JWT';
  accessToken?: string;
  refreshToken?: string;
  refreshUrl?: string;
  /** String values may contain `{refreshToken}`. @default {} */ refreshPayload?: Record<string, string>;
  refreshHeaders?: Record<string, string>;
  /** @default 'json' */ refreshPayloadEncoding?: 'json' | 'form';
  /** access-token expiry, epoch ms; -1 = unknown @default -1 */ expires?: number;
}
export interface HttpConfig {
  /** No url => nothing is uploaded (records stay queued). */ url?: string | null;
  /** @default 'POST' */ method?: HttpMethod;
  /** @default {} */ headers?: Record<string, string>;
  /** merged into the ROOT of every request body @default {} */ params?: Record<string, unknown>;
  /** @default true */ autoSync?: boolean;
  /** upload when queue >= threshold; 0 = every record @default 0 */ autoSyncThreshold?: number;
  /** @default false */ batchSync?: boolean;
  /** @default 100 */ maxBatchSize?: number;
  /** @default false */ disableAutoSyncOnCellular?: boolean;
  /** '.' = no wrapping @default 'location' */ rootProperty?: string;
  /** JSON text with `<%= name %>` placeholders */ locationTemplate?: string | null;
  /** used for event 'geofence'; falls back to locationTemplate */ geofenceTemplate?: string | null;
  /** ms @default 60000 */ timeout?: number;
  authorization?: AuthorizationConfig | null;
}
export interface PersistenceConfig {
  /** @default 7 */ maxDaysToPersist?: number;
  /** -1 = unlimited @default -1 */ maxRecordsToPersist?: number;
  /** merged into `extras` of every record at creation time @default {} */ extras?: Record<string, unknown>;
}
export interface AppConfig {
  /** @default true */ stopOnTerminate?: boolean;
  /** @default false */ startOnBoot?: boolean;
}
export interface NotificationActionButton { id: string; label: string }
export interface NotificationConfig {
  /** @default app label */ title?: string;
  /** @default 'Location tracking is active' */ text?: string;
  /** 'drawable/name' | 'mipmap/name' @default plugin icon 'drawable/lt_ic_notification' */ smallIcon?: string;
  largeIcon?: string;
  /** '#RRGGBB' */ color?: string;
  /** @default 'default' */ priority?: NotificationPriority;
  /** @default 'location_tracking' */ channelId?: string;
  /** @default 'Location tracking' */ channelName?: string;
  /** max 3; tap => 'notificationaction' event */ actions?: NotificationActionButton[];
}
export interface GeofenceConfig { /** @default true */ initialTriggerEntry?: boolean }
export interface LoggerConfig { /** @default 'info' */ logLevel?: LogLevel; /** @default 3 */ logMaxDays?: number }
export interface BackgroundPermissionRationale {
  /** defaults from plugin string resources */ title?: string; message?: string; positiveAction?: string; negativeAction?: string;
}
export interface Config {
  geolocation?: GeolocationConfig;
  activity?: ActivityConfig;
  heartbeat?: HeartbeatConfig;
  http?: HttpConfig;
  persistence?: PersistenceConfig;
  app?: AppConfig;
  notification?: NotificationConfig;
  geofence?: GeofenceConfig;
  logger?: LoggerConfig;
  backgroundPermissionRationale?: BackgroundPermissionRationale;
  /** Android only @default 'auto' */ locationProvider?: LocationProviderSetting;
}
export interface ReadyOptions {
  config?: Config;
  /** true: config = defaults + given config (every launch). false: given config applied only on the very first ready(); afterwards persisted config wins. @default true */
  reset?: boolean;
}
export interface State {
  enabled: boolean; trackingMode: TrackingMode; isMoving: boolean; odometer: number;
  backend: LocationBackend; lastRecordAt: string | null;
  /** fully populated with defaults */ config: Config;
}

/* ================= RECORDS (snake_case = identical to wire format) ================= */
export interface Coords {
  latitude: number; longitude: number; accuracy: number;
  altitude: number | null; altitude_accuracy: number | null;
  speed: number | null; speed_accuracy: number | null;
  heading: number | null; heading_accuracy: number | null;
}
export interface ProviderState {
  enabled: boolean; gps: boolean; network: boolean;
  permission: 'always' | 'when_in_use' | 'denied';
  accuracy: 'precise' | 'approximate' | 'none';
  backend: LocationBackend;
}
export interface Location {
  uuid: string;
  event: RecordEvent;
  /** fix time (ISO-8601 UTC ms); null if no location ever known */ timestamp: string | null;
  /** record creation time */ recorded_at: string;
  /** only present in HTTP bodies */ sent_at?: string;
  elapsed_realtime_ms: number;
  /** -1 if unavailable */ boot_count: number;
  is_moving: boolean;
  odometer: number;
  mock: boolean;
  coords: Coords | null;
  activity: { type: ActivityType; confidence: number };
  /** level 0..1, -1 unknown */ battery: { level: number; is_charging: boolean };
  backend: LocationBackend | null;
  extras?: Record<string, unknown>;
  /** event 'geofence' only */ geofence?: { identifier: string; action: GeofenceAction; extras?: Record<string, unknown> };
  /** event 'providerchange' only */ provider?: ProviderState;
  /** tracking_start: start|start_geofences|boot|restore|package_replaced; tracking_stop: stop|stop_on_stationary|stop_after_elapsed|terminate|permission_denied|service_start_failed|reboot|package_replaced */
  reason?: string;
}
export type LocationRecord = Location;
export interface InsertLocationInput {
  coords: Pick<Coords, 'latitude' | 'longitude'> & Partial<Coords>;
  timestamp?: string; event?: RecordEvent; is_moving?: boolean; extras?: Record<string, unknown>;
}

/* ================= GEOFENCES ================= */
/** Either (latitude, longitude, radius) = circle, or vertices = polygon. For polygons, getGeofences() also returns the computed enclosing circle. */
export interface Geofence {
  identifier: string;
  latitude?: number; longitude?: number; /** m */ radius?: number;
  /** [[lat,lng], ...] >= 3 points */ vertices?: [number, number][];
  /** @default true */ notifyOnEntry?: boolean;
  /** @default true */ notifyOnExit?: boolean;
  /** @default false */ notifyOnDwell?: boolean;
  /** ms @default 30000 */ loiteringDelay?: number;
  extras?: Record<string, unknown>;
}

/* ================= EVENTS ================= */
export interface MotionChangeEvent { isMoving: boolean; location: Location }
export interface ActivityChangeEvent { activity: ActivityType; confidence: number }
export interface HeartbeatEvent { location: Location }
export interface GeofenceEvent { identifier: string; action: GeofenceAction; location: Location; extras?: Record<string, unknown> }
export interface GeofencesChangeEvent { on: Geofence[]; off: string[] }
export interface HttpEvent { success: boolean; status: number; responseText: string; uuids: string[] }
export interface ConnectivityChangeEvent { connected: boolean; type: ConnectivityType }
export interface PowerSaveChangeEvent { isPowerSaveMode: boolean }
export interface EnabledChangeEvent { enabled: boolean }
export interface NotificationActionEvent { id: string }
export interface AuthorizationEvent { success: boolean; status: number; error?: string; response?: Record<string, unknown> }
export interface LocationTrackingEventMap {
  location: Location; motionchange: MotionChangeEvent; activitychange: ActivityChangeEvent;
  providerchange: ProviderState; heartbeat: HeartbeatEvent; geofence: GeofenceEvent;
  geofenceschange: GeofencesChangeEvent; http: HttpEvent; connectivitychange: ConnectivityChangeEvent;
  powersavechange: PowerSaveChangeEvent; enabledchange: EnabledChangeEvent;
  notificationaction: NotificationActionEvent; authorization: AuthorizationEvent;
}
export type LocationTrackingEventName = keyof LocationTrackingEventMap;

/* ================= MISC ================= */
export interface CurrentPositionOptions {
  /** @default 3 */ samples?: number; /** ms @default geolocation.locationTimeout */ timeout?: number;
  /** ms @default 0 */ maximumAge?: number; /** @default 'high' */ desiredAccuracy?: DesiredAccuracy;
  /** @default true */ persist?: boolean; extras?: Record<string, unknown>;
}
export interface WatchPositionOptions {
  /** ms @default 1000 */ interval?: number; /** @default 'high' */ desiredAccuracy?: DesiredAccuracy;
  /** @default false */ persist?: boolean; extras?: Record<string, unknown>;
}
export type WatchPositionCallback = (location: Location | null, error?: { code: ErrorCode; message: string }) => void;
export interface HeartbeatStatus {
  enabled: boolean; minInterval: number; maxInterval: number;
  lastRecordAt: string | null; lastHeartbeatAt: string | null; nextHeartbeatAt: string | null;
  strategy: HeartbeatStrategy; canScheduleExactAlarms: boolean; isIgnoringBatteryOptimizations: boolean;
  isDeviceIdleMode: boolean; isPowerSaveMode: boolean; pendingHeartbeats: number;
}
export interface BatteryOptimizationStatus { isIgnoringBatteryOptimizations: boolean; canScheduleExactAlarms: boolean; isDeviceIdleMode: boolean }
export interface PowerManagerInfo { manufacturer: string; available: boolean }
export interface DeviceInfo {
  platform: 'android' | 'web'; manufacturer: string; model: string; brand: string; osVersion: string; sdkInt: number;
  pluginVersion: string; gmsAvailable: boolean; hmsAvailable: boolean; backend: LocationBackend; packagedProviders: string[];
}
export interface Sensors { accelerometer: boolean; gyroscope: boolean; magnetometer: boolean; significantMotion: boolean; stepCounter: boolean; stepDetector: boolean; barometer: boolean }
export interface PermissionStatus { location: PermissionState; backgroundLocation: PermissionState; activityRecognition: PermissionState; notifications: PermissionState }
export interface LogQuery { start?: number; end?: number; level?: LogLevel; limit?: number; order?: 'asc' | 'desc' }

export interface LocationTrackingPlugin {
  ready(options?: ReadyOptions): Promise<State>;
  /** deep merge; arrays replaced; null resets key to default */ setConfig(options: { config: Config }): Promise<State>;
  reset(options?: { config?: Config }): Promise<State>;
  getState(): Promise<State>;
  start(): Promise<State>;
  startGeofences(): Promise<State>;
  stop(): Promise<State>;
  changePace(options: { isMoving: boolean }): Promise<void>;

  getCurrentPosition(options?: CurrentPositionOptions): Promise<Location>;
  watchPosition(options: WatchPositionOptions, callback: WatchPositionCallback): Promise<CallbackID>;
  clearWatch(options: { id: CallbackID }): Promise<void>;

  getOdometer(): Promise<{ odometer: number }>;
  setOdometer(options: { odometer: number }): Promise<{ odometer: number }>;
  resetOdometer(): Promise<{ odometer: number }>;

  getLocations(options?: { limit?: number }): Promise<{ locations: Location[] }>;
  getCount(): Promise<{ count: number }>;
  insertLocation(options: { location: InsertLocationInput }): Promise<{ uuid: string }>;
  destroyLocations(): Promise<{ count: number }>;
  destroyLocation(options: { uuid: string }): Promise<{ deleted: boolean }>;
  /** uploads whole queue now; resolves with uploaded records */ sync(): Promise<{ locations: Location[] }>;

  addGeofence(options: { geofence: Geofence }): Promise<void>;
  addGeofences(options: { geofences: Geofence[] }): Promise<void>;
  removeGeofence(options: { identifier: string }): Promise<void>;
  /** identifiers omitted (or null) = remove all; an empty array removes nothing */ removeGeofences(options?: { identifiers?: string[] }): Promise<void>;
  getGeofences(): Promise<{ geofences: Geofence[] }>;
  getGeofence(options: { identifier: string }): Promise<{ geofence: Geofence | null }>;
  geofenceExists(options: { identifier: string }): Promise<{ exists: boolean }>;

  getHeartbeatStatus(): Promise<HeartbeatStatus>;

  getProviderState(): Promise<ProviderState>;
  isPowerSaveMode(): Promise<{ isPowerSaveMode: boolean }>;
  getBatteryOptimizationStatus(): Promise<BatteryOptimizationStatus>;
  openBatteryOptimizationSettings(): Promise<{ opened: boolean }>;
  getPowerManagerInfo(): Promise<PowerManagerInfo>;
  openPowerManagerSettings(): Promise<{ opened: boolean }>;
  openLocationSettings(): Promise<{ opened: boolean }>;
  openAppSettings(): Promise<{ opened: boolean }>;
  getDeviceInfo(): Promise<DeviceInfo>;
  getSensors(): Promise<Sensors>;

  checkPermissions(): Promise<PermissionStatus>;
  /** default all four, order: location, notifications, activityRecognition, backgroundLocation */
  requestPermissions(options?: { permissions?: PermissionType[] }): Promise<PermissionStatus>;

  log(options: { level: Exclude<LogLevel, 'off'>; message: string }): Promise<void>;
  getLog(options?: LogQuery): Promise<{ log: string }>;
  destroyLog(): Promise<void>;
  uploadLog(options: { url: string; headers?: Record<string, string>; params?: Record<string, unknown> }): Promise<{ success: boolean; status: number }>;
  emailLog(options: { email: string; subject?: string }): Promise<void>;

  addListener(eventName: 'location', listenerFunc: (e: Location) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'motionchange', listenerFunc: (e: MotionChangeEvent) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'activitychange', listenerFunc: (e: ActivityChangeEvent) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'providerchange', listenerFunc: (e: ProviderState) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'heartbeat', listenerFunc: (e: HeartbeatEvent) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'geofence', listenerFunc: (e: GeofenceEvent) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'geofenceschange', listenerFunc: (e: GeofencesChangeEvent) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'http', listenerFunc: (e: HttpEvent) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'connectivitychange', listenerFunc: (e: ConnectivityChangeEvent) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'powersavechange', listenerFunc: (e: PowerSaveChangeEvent) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'enabledchange', listenerFunc: (e: EnabledChangeEvent) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'notificationaction', listenerFunc: (e: NotificationActionEvent) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'authorization', listenerFunc: (e: AuthorizationEvent) => void): Promise<PluginListenerHandle>;
  removeAllListeners(): Promise<void>;
}
```

**NOT_READY rule.** The bridge rejects every method except the following until `ready()` has resolved in the current process (a process-wide flag): `ready`, `getState`, `checkPermissions`, `requestPermissions`, `getDeviceInfo`, `getSensors`, `log`, `getLog`, `getProviderState`, and the `open*Settings` methods. The web stub applies the same rule to the methods it implements (`setConfig`, `reset`, `getCurrentPosition`, `watchPosition`, `clearWatch`).

**Method semantics fixed at integration.**
- `start()` / `startGeofences()` resolve after the `tracking_start` record and `EnabledChange(true)`; the initial `motionchange` follows asynchronously (≤ `locationTimeout` + 5 s).
- `ready()` restores tracking (reason `restore`) when `runtime.enabled` is true but no session runs in this process.
- `removeGeofences()`: a missing or null `identifiers` removes all; an empty array removes nothing.
- `insertLocation()`: `RecordFactory.fromExternal`, then `locationStore.insert` and `syncer.onRecordInserted`. It bypasses the `RecordSink`: no event, no runtime update, and the heartbeat window is not restarted (an inserted record is not evidence that tracking runs).

**How the TypeScript entry points expose events.**
- `src/plugin.ts` calls `registerPlugin<LocationTrackingPlugin>('LocationTracking', { web: () => import('./web').then(m => new m.LocationTrackingWeb()) })`.
- `src/events.ts` imports from `plugin.ts`. It exports `Events` (a const map of event names) and standalone typed helpers: `onLocation`, `onMotionChange`, `onActivityChange`, `onProviderChange`, `onHeartbeat`, `onGeofence`, `onGeofencesChange`, `onHttp`, `onConnectivityChange`, `onPowerSaveChange`, `onEnabledChange`, `onNotificationAction`, `onAuthorization`. Each returns `Promise<PluginListenerHandle>`. It also exports a generic `on<K extends LocationTrackingEventName>(name, cb)`.
- `src/index.ts` re-exports the definitions, `LocationTracking` and the helpers.
- Do **not** attach helpers to the plugin object: Capacitor's plugin proxy turns unknown properties into native calls.
- `plugin.ts` is a separate file so that `index` and `events` do not import each other.

**Web stub.**
- `getCurrentPosition`, `watchPosition` and `clearWatch` use `navigator.geolocation`. They map results to the `Location` shape, with `boot_count: -1`, `backend: 'web'`, and uuids from `crypto.randomUUID()`. `getCurrentPosition` (unless `persist: false`) and `watchPosition` with `persist: true` also emit `location` events; nothing is stored or uploaded, and `lastRecordAt` stays null. `getCurrentPosition` runs its own overall deadline (best fix so far at the deadline; `timeout: 0` rejects at once). `WatchPositionOptions.interval` is ignored.
- `ready`, `setConfig`, `reset` and `getState` resolve an in-memory `State` built from the given config.
- `checkPermissions` uses `navigator.permissions` when available (`'prompt'` when it cannot tell). The other three permission types are always `'granted'`.
- `requestPermissions` asks for a fix so that the browser shows its prompt (only when `location` is requested and still `'prompt'`), gives up after 60 s, and reports the result.
- `getDeviceInfo` parses `navigator.userAgent` (`platform: 'web'`, `sdkInt: -1`).
- Errors are `CapacitorException`s whose `code` is an `ErrorCode`. `plugin.ts` memoizes the web factory, so concurrent first calls share one instance.
- Listener methods come from `WebPlugin`.
- Everything else throws `this.unimplemented('Not implemented on web.')` (`UNIMPLEMENTED`).
- On iOS, Capacitor rejects every call with "not implemented on ios".

---

## 2. Record wire format (default, no template)

`RecordJson.toJson(record, sentAt)` (SCAFFOLD) builds each record. JS receives records in the same shape, minus `sent_at`.

General rules:
- Timestamps are ISO-8601 UTC with milliseconds: `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`.
- Unknown numeric coordinate fields are `null`.
- Keys are always present, except the event-specific keys (`geofence`, `provider`, `reason`) and the optional `extras`.

**Single record** (`batchSync:false`, `rootProperty:'location'`). `http.params` are merged into the root:

```json
{
  "location": {
    "uuid": "0d6c6a1e-6c1e-4b8e-9d8f-2b6f3f0f7a11",
    "event": "location",
    "timestamp": "2026-09-26T10:15:30.123Z",
    "recorded_at": "2026-09-26T10:15:30.456Z",
    "sent_at": "2026-09-26T10:15:45.001Z",
    "elapsed_realtime_ms": 86400123,
    "boot_count": 42,
    "is_moving": true,
    "odometer": 1532.4,
    "mock": false,
    "coords": { "latitude": 24.7136, "longitude": 46.6753, "accuracy": 5.2,
                "altitude": 612.3, "altitude_accuracy": 3.0, "speed": 13.4, "speed_accuracy": 0.8,
                "heading": 271.5, "heading_accuracy": 5.0 },
    "activity": { "type": "in_vehicle", "confidence": 92 },
    "battery": { "level": 0.81, "is_charging": false },
    "backend": "gms",
    "extras": { "driver_id": 7 }
  },
  "device_id": "abc"
}
```

**Variants.** Each differs from the single record only in the keys listed.
- **Heartbeat:** `"event":"heartbeat"`.
  - `coords` and `timestamp` come from the last known location, so `timestamp` may be old.
  - If no location was ever known, both are `null`.
  - `recorded_at` is when the heartbeat was created; `sent_at` is when it was uploaded, so the server can detect a late delivery.
- **Audit records.** Both carry the last known coords, or null.
  - `"event":"tracking_start"` with `"reason":"start"|"start_geofences"|"boot"|"restore"|"package_replaced"`.
  - `"event":"tracking_stop"` with `"reason":"stop"|"stop_on_stationary"|"stop_after_elapsed"|"terminate"|"permission_denied"|"service_start_failed"|"reboot"|"package_replaced"`. `reboot` / `package_replaced`: tracking was enabled before a reboot or an app update and is not resumed because `app.startOnBoot` is false (`TrackingEngine.endWithoutRestore`). `permission_denied`: location permission missing on restore, or `start()`/`startGeofences()` could not start the service (the call rejects with `PERMISSION_DENIED`). `service_start_failed`: the foreground service was refused on restore, or failed to enter the foreground after `ServiceController.start()` returned true.
- **Providerchange:** `"event":"providerchange","provider":{"enabled":false,"gps":false,"network":true,"permission":"always","accuracy":"precise","backend":"gms"}`.
- **Geofence:** `"event":"geofence","geofence":{"identifier":"home","action":"ENTER","extras":{...}}`, with the coords of the fix that triggered it.
- **`motionchange`:** the fix at the moment of the state change, with `is_moving` set to the new state.
- **`current_position` and `watch_position`:** positions that were persisted.

**Batch** (`batchSync:true`): `{"location":[{...},{...}], ...params}`, with up to `maxBatchSize` records, oldest first.

**Params** never overwrite a key the body already has (the record data wins); params that are not a JSON object are ignored.

**`rootProperty:"."`** (a blank `rootProperty` behaves the same):
- A single record's fields are merged into the root together with params. A template that renders an array is sent bare.
- A batch is sent as a bare JSON array, and params are ignored.

**Request details.**
- Headers:
  - `Content-Type: application/json; charset=utf-8`;
  - then `http.headers` (same name, ignoring case, replaces the earlier value);
  - then `Authorization: Bearer <accessToken>` when authorization is configured and a token is available, unless the headers already contain `Authorization` (which disables the JWT handling).
- Outcomes:
  - 2xx: the records are deleted (an unreadable body still counts as success).
  - 401: refresh the token, then retry once, unless a refresh was already attempted for this upload (at most one refresh per upload, across all tries).
  - 5xx, 429, or an I/O error (timeout, no connection): the same upload tries again after 2, 4 and 8 s (at most 4 tries, R2-Q18), holding the upload lock; then as below.
  - Any other status, or the last failed try: the records stay queued, and `attempts` and `last_attempt_at` are updated once per upload.
- One `http` event is emitted per HTTP request (401 + refresh + retry = 2 events; one per try). An invalid `http.url` is treated as no URL.

**Templates.**
- `<%= name %>` tolerates surrounding whitespace. It is substituted as a raw JSON literal:
  - numbers and booleans are inserted bare (non-finite numbers as `null`);
  - null is inserted as `null`; a null placeholder wrapped exactly in quotes (`"<%= reason %>"`) replaces the quotes too, giving JSON `null`;
  - strings are JSON-escaped but inserted without quotes, so users write `"<%= timestamp %>"`.
- Placeholders: `uuid, event, timestamp, recorded_at, sent_at, latitude, longitude, accuracy, altitude, altitude_accuracy, speed, speed_accuracy, heading, heading_accuracy, is_moving, odometer, mock, activity.type, activity.confidence, battery.level, battery.is_charging, elapsed_realtime_ms, boot_count, backend, reason, geofence.identifier, geofence.action, provider.enabled, provider.gps, provider.network, provider.permission, extras`. `extras` is substituted as JSON object text (`{}` when the record has none).
- An unknown placeholder becomes an empty string and logs a warning.
- The rendered text must be a JSON object or array, validated by a strict parser (org.json is too lenient); otherwise the default shape is used and an error is logged.

**Priority records.** `RecordEvent.isPriority` marks `heartbeat`, `tracking_start`, `tracking_stop` and `providerchange`.
- Priority records are uploaded immediately whenever `http.url` is set, ignoring `autoSync`, `autoSyncThreshold`, batch waiting and `disableAutoSyncOnCellular`.
- If on cellular with `disableAutoSyncOnCellular`, only priority records are sent. Otherwise the whole queue is drained in order.
- Normal records follow `autoSync` and the threshold (queue ≥ `max(1, autoSyncThreshold)`).
- A pass stops at the first failed request, except that when the server rejected (non-2xx, not a network error) an upload in a whole-queue pass, the queued priority records are still sent in the same pass. Normal records behind a rejected normal record stay blocked until it is accepted or pruned.
- One upload worker; automatic passes and `sync()` share one mutex; triggers during a pass collapse into one more pass.
- Queued records are retried (there is no timer):
  - on the next insert (every heartbeat is one);
  - when connectivity returns (`ConnectivityChange(connected=true)`, including Doze/Data Saver unblocking);
  - when the syncer starts (first tracking start or restore in a process);
  - on a manual `sync()`, which ignores the policy and connectivity.

**JWT.**
- **When to refresh:** before a request if the access token is missing or `expires > 0 && now >= expires - 60s` (a refreshed token with ≤ 60 s lifetime is not refreshed again until it expires), or after a 401 unless a refresh was already attempted for this upload. A refresh needs `refreshUrl`.
- **Request:** POST to `refreshUrl` with `refreshHeaders` (no Authorization header). The body is `refreshPayload` with `{refreshToken}` substituted, encoded as JSON or form.
- **Response fields read:**
  - `accessToken|access_token`;
  - `refreshToken|refresh_token` (optional);
  - `expires|expires_at` (epoch; interpreted as seconds if less than 1e12; or an ISO-8601 string) or `expires_in` (relative seconds); none → `expires = -1`.
- **After a refresh:** new tokens are persisted with `configStore.update`. An `authorization` event is emitted for every attempt, on success and on failure. Token values are never logged.
- **Concurrency:** a single-flight mutex allows at most one refresh per request.

---

## 3. Android module layout

The root is `android/src/main/java/com/brickssoft/locationtracking/`. In the table:
- **S** means SCAFFOLD: a complete implementation or a contract.
- **Un** means the file belongs to unit n. The scaffold ships a compiling, non-throwing stub under the final name.

| Path | Owner | Content |
|---|---|---|
| `LocationTrackingPlugin.kt` | U2 | `@CapacitorPlugin` (annotation and aliases fixed by S, see below); stub methods call `call.unimplemented()` |
| `bridge/**` (`PluginHandlers.kt`, `OptionParsers.kt`, `PluginPermissionHost.kt`, `WatchRegistry.kt`) | U2 | |
| `core/Components.kt`, `Clock.kt`, `AppDispatchers.kt`, `EventBus.kt`, `TrackingEvent.kt`, `Logger.kt`, `Errors.kt`, `Iso8601.kt`, `Constants.kt` | S | |
| `model/TrackedLocation.kt`, `Record.kt`, `GeofenceSpec.kt`, `DeviceModels.kt`, `ModelJson.kt` (RecordJson, GeofenceJson, EventJson, misc JSON) | S | fully implemented and tested |
| `config/Config.kt`, `State.kt`, `ConfigStore.kt` | S | |
| `config/ConfigJson.kt`, `SharedPrefsConfigStore.kt`, `ConfigValidator.kt` | U3 | |
| `provider/Backends.kt` (all provider interfaces and sinks) | S | |
| `provider/DefaultProviderFactory.kt`, `provider/android/**` | U6 | |
| `provider/gms/**` | U4 | |
| `provider/hms/**` | U5 | |
| `engine/TrackingEngine.kt` | S | |
| `engine/DefaultTrackingEngine.kt`, `MotionStateMachine.kt`, `ElasticDistance.kt`, `TrackingTimers.kt` | U7 | |
| `service/ServiceController.kt` | S | |
| `service/LocationTrackingService.kt`, `DefaultServiceController.kt`, `NotificationFactory.kt`, `BootReceiver.kt`, `NotificationActionReceiver.kt` | U8 | |
| `processing/Processing.kt` | S | |
| `processing/DefaultLocationProcessor.kt`, `KalmanFilter.kt`, `DefaultOdometer.kt`, `DefaultRecordFactory.kt` | U9 | |
| `record/RecordSink.kt` (interface and `DefaultRecordSink`) | S | implemented |
| `data/Stores.kt` | S | |
| `data/TrackingDatabase.kt`, `SqliteLocationStore.kt`, `SqliteGeofenceStore.kt` | U10 | |
| `http/HttpSyncer.kt` | S | |
| `http/OkHttpSyncer.kt`, `SyncPolicy.kt`, `BodyBuilder.kt`, `TemplateRenderer.kt`, `AuthorizationManager.kt` | U11 | |
| `heartbeat/HeartbeatScheduler.kt` | S | |
| `heartbeat/DefaultHeartbeatScheduler.kt`, `HeartbeatWindow.kt`, `HeartbeatAlarmReceiver.kt` | U12 | |
| `geofence/GeofenceManager.kt` | S | |
| `geofence/DefaultGeofenceManager.kt`, `PolygonMath.kt`, `DwellTracker.kt` | U13 | |
| `device/DeviceMonitor.kt` (DeviceMonitor and DeviceInfoProvider interfaces) | S | |
| `device/DefaultDeviceMonitor.kt`, `DefaultDeviceInfoProvider.kt` | U14 | |
| `settings/DeviceSettings.kt` | S | |
| `settings/DefaultDeviceSettings.kt`, `OemPowerManagers.kt` | U15 | |
| `permission/PermissionManager.kt` (PermissionManager and PermissionHost) | S | |
| `permission/DefaultPermissionManager.kt`, `BackgroundRationaleDialog.kt` | U15 | |
| `position/PositionService.kt` | S | |
| `position/DefaultPositionService.kt` | U16 | |
| `logging/LogStore.kt`, `LogFileProvider.kt` (empty `FileProvider` subclass) | S | |
| `logging/FileLogger.kt`, `LogFormatter.kt` | U17 | |

Stub receivers and services that the manifest names, for example `provider/gms/GmsActivityReceiver`, exist in the scaffold as empty classes inside the owning unit's package.

**Resources (S).** All resources carry the `lt_` prefix, which `resourcePrefix` enforces.
- `res/xml/lt_file_paths.xml`: `<files-path name="lt_logs" path="location-tracking-logs/"/>` and `<cache-path name="lt_logs_cache" path="location-tracking-logs/"/>`.
- `res/drawable/lt_ic_notification.xml` (vector).
- `res/values/lt_strings.xml`: `lt_notification_text`, `lt_notification_channel_name`, `lt_bg_rationale_title|message|positive|negative`.

**Tests.**
- The fakes live in `android/src/test/java/com/brickssoft/locationtracking/testing/**` (S).
- Scaffold tests:
  - `model/RecordJsonTest` (golden wire JSON);
  - `model/EventJsonTest`;
  - `record/DefaultRecordSinkTest`;
  - `ScaffoldWiringTest`: Robolectric with `@Config(sdk=[29,30,33,34,35])`. It calls `Components.get(app)` and touches every lazy val. It must stay green after every unit.
- `src/test/resources/robolectric.properties` contains `sdk=34`.

### Kotlin contracts (SCAFFOLD)

```kotlin
// ---- core
interface Clock { fun now(): Long; fun elapsedRealtime(): Long; fun bootCount(): Int }   // bootCount: Settings.Global.BOOT_COUNT or -1
class AppDispatchers(val main: CoroutineDispatcher, val io: CoroutineDispatcher, val engine: CoroutineDispatcher) {
  companion object { val DEFAULT = AppDispatchers(Dispatchers.Main, Dispatchers.IO, Dispatchers.Default.limitedParallelism(1)) } }
fun interface Subscription { fun cancel() }
interface EventBus { fun emit(event: TrackingEvent); fun subscribe(listener: (TrackingEvent) -> Unit): Subscription } // sync, emitter's thread; SimpleEventBus = CopyOnWriteArrayList
enum class LogLevel { OFF, ERROR, WARN, INFO, DEBUG, VERBOSE }
interface LogSink { fun write(level: LogLevel, tag: String, message: String, error: Throwable?) }
object Logger { @Volatile var sink: LogSink? = null; fun e(tag: String, msg: String, t: Throwable? = null); fun w(..); fun i(..); fun d(..); fun v(..) } // also android.util.Log
enum class ErrorCode { NOT_READY, PERMISSION_DENIED, LOCATION_DISABLED, TIMEOUT, UNAVAILABLE, INVALID_ARGUMENT, NOT_FOUND, NO_URL, HTTP_ERROR, NETWORK_ERROR, TOO_MANY_GEOFENCES, NO_ACTIVITY, IO_ERROR, UNIMPLEMENTED, INTERNAL }
class TrackingException(val code: ErrorCode, message: String, cause: Throwable? = null) : Exception(message, cause)

sealed interface TrackingEvent {                         // JS name / payload built by EventJson (S)
  data class Location(val record: Record) : TrackingEvent                     // "location"
  data class MotionChange(val isMoving: Boolean, val record: Record) : TrackingEvent
  data class ActivityChange(val activity: ActivitySample) : TrackingEvent
  data class ProviderChange(val state: ProviderState) : TrackingEvent
  data class Heartbeat(val record: Record) : TrackingEvent
  data class Geofence(val identifier: String, val action: GeofenceAction, val record: Record, val extras: String?) : TrackingEvent
  data class GeofencesChange(val on: List<GeofenceSpec>, val off: List<String>) : TrackingEvent
  data class Http(val result: HttpResult) : TrackingEvent
  data class ConnectivityChange(val connectivity: Connectivity) : TrackingEvent
  data class PowerSaveChange(val isPowerSaveMode: Boolean) : TrackingEvent
  data class EnabledChange(val enabled: Boolean) : TrackingEvent
  data class NotificationAction(val id: String) : TrackingEvent
  data class Authorization(val success: Boolean, val status: Int, val error: String?, val responseJson: String?) : TrackingEvent
}

// ---- model
data class TrackedLocation(val latitude: Double, val longitude: Double, val accuracy: Float,
  val altitude: Double? = null, val altitudeAccuracy: Float? = null, val speed: Float? = null, val speedAccuracy: Float? = null,
  val heading: Float? = null, val headingAccuracy: Float? = null, val time: Long, val elapsedRealtimeNanos: Long = 0,
  val provider: String? = null, val isMock: Boolean = false) { companion object { fun from(l: android.location.Location): TrackedLocation } }
enum class RecordEvent(val wire: String, val isPriority: Boolean) { LOCATION("location",false), MOTIONCHANGE("motionchange",false),
  CURRENT_POSITION("current_position",false), WATCH_POSITION("watch_position",false), HEARTBEAT("heartbeat",true), GEOFENCE("geofence",false),
  TRACKING_START("tracking_start",true), TRACKING_STOP("tracking_stop",true), PROVIDERCHANGE("providerchange",true) }
enum class ActivityType(val wire: String) { STILL("still"), ON_FOOT("on_foot"), WALKING("walking"), RUNNING("running"), ON_BICYCLE("on_bicycle"), IN_VEHICLE("in_vehicle"), UNKNOWN("unknown") }
data class ActivitySample(val type: ActivityType, val confidence: Int) { companion object { val UNKNOWN = ActivitySample(ActivityType.UNKNOWN, 0) } }
data class BatterySnapshot(val level: Float, val isCharging: Boolean)          // level 0..1, -1 unknown
enum class ProviderKind(val wire: String) { GMS("gms"), HMS("hms"), ANDROID("android") }
enum class GeofenceAction { ENTER, EXIT, DWELL }
data class GeofenceHit(val identifier: String, val action: GeofenceAction, val extras: String?)
data class Record(val uuid: String, val event: RecordEvent, val location: TrackedLocation?, val recordedAt: Long,
  val elapsedRealtimeMs: Long, val bootCount: Int, val isMoving: Boolean, val odometer: Double, val activity: ActivitySample,
  val battery: BatterySnapshot, val backend: ProviderKind?, val extras: String? = null /*JSON object text*/,
  val geofence: GeofenceHit? = null, val provider: ProviderState? = null, val reason: String? = null)
data class LatLng(val latitude: Double, val longitude: Double)
data class GeofenceSpec(val identifier: String, val latitude: Double, val longitude: Double, val radius: Float, // polygon: enclosing circle (0 until GeofenceManager computes it)
  val vertices: List<LatLng>? = null, val notifyOnEntry: Boolean = true, val notifyOnExit: Boolean = true, val notifyOnDwell: Boolean = false,
  val loiteringDelay: Long = 30_000, val extras: String? = null) { val isPolygon get() = vertices != null }
enum class PermissionLevel(val wire: String) { ALWAYS("always"), WHEN_IN_USE("when_in_use"), DENIED("denied") }
enum class AccuracyLevel(val wire: String) { PRECISE("precise"), APPROXIMATE("approximate"), NONE("none") }
data class ProviderState(val enabled: Boolean, val gps: Boolean, val network: Boolean, val permission: PermissionLevel, val accuracy: AccuracyLevel, val backend: ProviderKind)
enum class ConnectivityType { WIFI, CELLULAR, ETHERNET, OTHER, NONE }
data class Connectivity(val connected: Boolean, val type: ConnectivityType)
data class HttpResult(val success: Boolean, val status: Int, val responseText: String, val uuids: List<String>)
enum class HeartbeatStrategy { EXACT, LISTENER_WITH_BACKUP, IDLE_PACED, DISABLED }
data class HeartbeatStatus(val enabled: Boolean, val minInterval: Int, val maxInterval: Int, val lastRecordAt: Long?, val lastHeartbeatAt: Long?,
  val nextHeartbeatAt: Long?, val strategy: HeartbeatStrategy, val canScheduleExactAlarms: Boolean, val isIgnoringBatteryOptimizations: Boolean,
  val isDeviceIdleMode: Boolean, val isPowerSaveMode: Boolean, val pendingHeartbeats: Int)
// also: DeviceInfo, Sensors, PowerManagerInfo, BatteryOptimizationStatus data classes mirroring TS

// ---- config (Config.kt mirrors TS 1:1, same field names, defaults as documented; JSON-valued fields stored as JSON text:
//   http.params, persistence.extras, authorization.refreshPayload : String = "{}"; headers: Map<String,String>)
data class Config(val geolocation: GeolocationConfig = GeolocationConfig(), val activity: ActivityConfig = ActivityConfig(),
  val heartbeat: HeartbeatConfig = HeartbeatConfig(), val http: HttpConfig = HttpConfig(), val persistence: PersistenceConfig = PersistenceConfig(),
  val app: AppConfig = AppConfig(), val notification: NotificationConfig = NotificationConfig(), val geofence: GeofenceConfig = GeofenceConfig(),
  val logger: LoggerConfig = LoggerConfig(), val backgroundPermissionRationale: BackgroundPermissionRationale = BackgroundPermissionRationale(),
  val locationProvider: LocationProviderSetting = LocationProviderSetting.AUTO)
enum class TrackingMode { LOCATION, GEOFENCES }
data class RuntimeState(val enabled: Boolean = false, val trackingMode: TrackingMode = TrackingMode.LOCATION, val isMoving: Boolean = false,
  val odometer: Double = 0.0, val activity: ActivitySample = ActivitySample.UNKNOWN, val lastLocation: TrackedLocation? = null,
  val lastRecordAt: Long? = null, val lastRecordElapsed: Long? = null, val lastRecordBootCount: Int? = null, val lastHeartbeatAt: Long? = null,
  val trackingStartedAt: Long? = null, val providerState: ProviderState? = null, val didReady: Boolean = false)
data class State(val config: Config, val runtime: RuntimeState, val backend: ProviderKind)
interface ConfigStore {                 // U3: SharedPreferences "location_tracking_prefs", loads synchronously in constructor
  val config: StateFlow<Config>
  val runtime: StateFlow<RuntimeState>
  fun ready(json: JSONObject?, reset: Boolean): Config
  fun merge(json: JSONObject): Config                     // setConfig
  fun reset(json: JSONObject?): Config
  fun update(transform: (Config) -> Config): Config       // internal writers (JWT refresh)
  fun updateRuntime(transform: (RuntimeState) -> RuntimeState): RuntimeState
}
// U3 also: object ConfigJson { fun parse(json: JSONObject, base: Config = Config()): Config; fun toJson(c: Config): JSONObject; fun stateToJson(s: State): JSONObject }

// ---- providers
data class LocationRequestSpec(val accuracy: DesiredAccuracy, val intervalMs: Long, val fastestIntervalMs: Long, val distanceFilterM: Float)
fun interface LocationListener { fun onLocations(locations: List<TrackedLocation>) }   // any thread
interface LocationBackend {
  val kind: ProviderKind
  fun requestUpdates(spec: LocationRequestSpec, listener: LocationListener)   // multiple listeners; same listener => replace;
                                                                              // distanceFilterM 0 = no filter; never throws (logs)
  fun removeUpdates(listener: LocationListener)
  suspend fun getLastLocation(): TrackedLocation?                             // may throw PERMISSION_DENIED; callers catch
  suspend fun getCurrentLocation(accuracy: DesiredAccuracy, timeoutMs: Long): TrackedLocation?   // null on timeout; may throw PERMISSION_DENIED
}
interface ActivityBackend { val kind: ProviderKind; val isSupported: Boolean; fun start(intervalMs: Long): Boolean; fun stop() }
data class OsGeofence(val id: String, val latitude: Double, val longitude: Double, val radius: Float, val onEntry: Boolean,
  val onExit: Boolean, val onDwell: Boolean, val loiteringDelayMs: Int, val initialTriggerEntry: Boolean)
data class OsGeofenceTransition(val id: String, val action: GeofenceAction, val location: TrackedLocation?)
interface GeofenceBackend { val kind: ProviderKind; val supportsDwell: Boolean
  suspend fun add(regions: List<OsGeofence>); suspend fun remove(ids: List<String>); suspend fun removeAll() }   // throw TrackingException
interface ProviderBundle { val kind: ProviderKind; fun isAvailable(): Boolean; fun location(): LocationBackend; fun activity(): ActivityBackend; fun geofence(): GeofenceBackend }
// Reflection targets, public ctor (Context): provider.gms.GmsProviderBundle, provider.hms.HmsProviderBundle, provider.android.AndroidProviderBundle
interface ProviderFactory { val kind: ProviderKind; fun location(): LocationBackend; fun activity(): ActivityBackend; fun geofence(): GeofenceBackend
  fun isAvailable(kind: ProviderKind): Boolean; fun reselect(): Boolean /* true if kind changed */ }
interface ActivitySink { suspend fun onActivitySamples(samples: List<ActivitySample>) }                 // implemented by TrackingEngine
interface GeofenceTransitionSink { suspend fun onGeofenceTransitions(transitions: List<OsGeofenceTransition>) }  // by GeofenceManager
// Activity / geofence PendingIntent receivers (GMS, HMS, Android) deliver to Components.get(ctx).engine (ActivitySink)
// and Components.get(ctx).geofences (GeofenceTransitionSink) via goAsync() + Components.scope.

// ---- processing (U9)
sealed interface FilterResult { data class Accepted(val location: TrackedLocation) : FilterResult; data class Rejected(val reason: String) : FilterResult }
interface LocationProcessor { fun process(raw: TrackedLocation, isMoving: Boolean): FilterResult; fun reset() }
interface Odometer { val value: Double; fun onLocation(location: TrackedLocation); fun set(value: Double); fun reset() }   // persists via updateRuntime
interface RecordFactory {
  fun create(event: RecordEvent, location: TrackedLocation?, extras: String? = null, geofence: GeofenceHit? = null,
             provider: ProviderState? = null, reason: String? = null): Record   // merges persistence.extras; reads runtime (isMoving, odometer, activity), battery, clock
  fun fromExternal(input: JSONObject): Record                                   // insertLocation
}

// ---- record sink (S, implemented)
interface RecordSink { suspend fun submit(record: Record): Record }
/* DefaultRecordSink(store, configStore, heartbeat, syncer, events):
   1 store.insert(record)  2 updateRuntime{lastRecordAt/Elapsed/BootCount, lastLocation = record.location ?: it.lastLocation,
   lastHeartbeatAt if HEARTBEAT}  3 heartbeat.onRecordRecorded(record)  4 emit: LOCATION/CURRENT_POSITION/WATCH_POSITION -> Location;
   MOTIONCHANGE -> Location + MotionChange; HEARTBEAT -> Heartbeat; others: producer emits  5 syncer.onRecordInserted(record) */

// ---- data (U10)
interface LocationStore {
  suspend fun insert(record: Record)
  suspend fun list(limit: Int = -1, events: Set<RecordEvent>? = null): List<Record>   // oldest first
  suspend fun count(events: Set<RecordEvent>? = null): Int
  suspend fun delete(uuids: Collection<String>): Int
  suspend fun deleteAll(): Int
  suspend fun markAttempt(uuids: Collection<String>, at: Long)
}   // pruning (maxDaysToPersist / maxRecordsToPersist) is internal: on open + every 50 inserts
data class GeofenceRuntime(val insideCircle: Boolean, val insidePolygon: Boolean, val enteredAt: Long?)
interface GeofenceStore {
  suspend fun upsert(geofences: List<GeofenceSpec>); suspend fun remove(ids: Collection<String>): Int; suspend fun removeAll(): Int
  suspend fun all(): List<GeofenceSpec>; suspend fun get(id: String): GeofenceSpec?; suspend fun count(): Int
  suspend fun setRuntime(id: String, runtime: GeofenceRuntime); suspend fun runtimes(): Map<String, GeofenceRuntime>
}

// ---- http (U11)
interface HttpSyncer {
  fun start()                                   // idempotent; subscribes to ConnectivityChange
  fun onRecordInserted(record: Record)          // non-blocking policy (priority/threshold/cellular)
  suspend fun sync(): List<Record>              // manual; throws NO_URL / HTTP_ERROR / NETWORK_ERROR
}

// ---- heartbeat (U12)
enum class HeartbeatTrigger { EXACT_ALARM, LISTENER_ALARM, BACKUP_ALARM }
interface HeartbeatScheduler {
  fun start(); fun stop()                       // start: window from runtime.lastRecord* (or now); observes config itself
  fun onRecordRecorded(record: Record)          // every persisted record restarts the window
  suspend fun onAlarm(trigger: HeartbeatTrigger)
  suspend fun status(): HeartbeatStatus
}

// ---- geofence (U13)
interface GeofenceManager : GeofenceTransitionSink {
  suspend fun add(geofences: List<GeofenceSpec>)          // >100 total -> TOO_MANY_GEOFENCES; emits GeofencesChange
  suspend fun remove(ids: List<String>); suspend fun removeAll()
  suspend fun list(): List<GeofenceSpec>; suspend fun get(id: String): GeofenceSpec?
  suspend fun onTrackingStarted(mode: TrackingMode); suspend fun onTrackingStopped()   // (un)register with OS
  fun onLocation(location: TrackedLocation)               // polygon hit-test + synthesized dwell
  val needsContinuousLocation: StateFlow<Boolean>         // true while inside any polygon's enclosing circle
}

// ---- device (U14) / settings (U15)
interface DeviceMonitor {
  fun start()                                              // idempotent: PROVIDERS_CHANGED, NetworkCallback, POWER_SAVE_MODE_CHANGED, DEVICE_IDLE_MODE_CHANGED
  suspend fun checkProviderState(reason: String)          // diff vs runtime.providerState -> ProviderChange event (+ providerchange record if enabled)
  fun providerState(): ProviderState; fun connectivity(): Connectivity; fun battery(): BatterySnapshot
  fun isPowerSaveMode(): Boolean; fun isDeviceIdleMode(): Boolean; fun isIgnoringBatteryOptimizations(): Boolean; fun canScheduleExactAlarms(): Boolean
}
interface DeviceInfoProvider { fun deviceInfo(): DeviceInfo; fun sensors(): Sensors }
interface DeviceSettings { fun openBatteryOptimizationSettings(activity: Activity?): Boolean; fun powerManagerInfo(): PowerManagerInfo
  fun openPowerManagerSettings(activity: Activity?): Boolean; fun openLocationSettings(activity: Activity?): Boolean; fun openAppSettings(activity: Activity?): Boolean }

// ---- permissions (U15; host implemented by U2)
enum class PermissionType(val alias: String) { LOCATION("location"), BACKGROUND_LOCATION("backgroundLocation"), ACTIVITY_RECOGNITION("activityRecognition"), NOTIFICATIONS("notifications") }
enum class PermissionState(val js: String) { GRANTED("granted"), DENIED("denied"), PROMPT("prompt"), PROMPT_WITH_RATIONALE("prompt-with-rationale") }
interface PermissionHost { val activity: Activity?
  fun requestAliases(aliases: List<String>, onDone: () -> Unit) }       // wraps requestPermissionForAliases + @PermissionCallback
interface PermissionManager {
  fun status(): Map<PermissionType, PermissionState>                    // API-level aware (bg<29 = fg; AR<29 granted; notif<33 granted)
  fun hasForegroundLocation(): Boolean; fun hasBackgroundLocation(): Boolean; fun hasActivityRecognition(): Boolean; fun hasNotifications(): Boolean
  fun request(host: PermissionHost, types: List<PermissionType>, rationale: BackgroundPermissionRationale,
              onDone: (Map<PermissionType, PermissionState>) -> Unit)  // main thread
}

// ---- position (U16) / service (U8) / engine (U7) / logging (U17)
data class CurrentPositionOptions(val samples: Int = 3, val timeoutMs: Long? = null, val maximumAgeMs: Long = 0, val desiredAccuracy: DesiredAccuracy = DesiredAccuracy.HIGH, val persist: Boolean = true, val extras: String? = null)
data class WatchPositionOptions(val intervalMs: Long = 1000, val desiredAccuracy: DesiredAccuracy = DesiredAccuracy.HIGH, val persist: Boolean = false, val extras: String? = null)
interface PositionService { suspend fun getCurrentPosition(o: CurrentPositionOptions): Record
  fun watchPosition(id: String, o: WatchPositionOptions, callback: (Record?, TrackingException?) -> Unit); fun clearWatch(id: String): Boolean; fun clearAllWatches() }
interface ServiceController { val isRunning: Boolean; fun start(): Boolean /* false if OS refused (or API 34+ without location permission), logged */; fun stop(); fun refreshNotification() }
interface TrackingEngine : ActivitySink {
  suspend fun ready(config: JSONObject?, reset: Boolean): State; suspend fun setConfig(config: JSONObject): State; suspend fun reset(config: JSONObject?): State
  suspend fun start(): State; suspend fun startGeofences(): State; suspend fun stop(): State; suspend fun changePace(isMoving: Boolean)
  fun state(): State
  suspend fun restore(reason: String)      // "restore" | "boot" | "package_replaced": cold process while enabled
  suspend fun onTerminate()                // task removed
  suspend fun onServiceStartFailed(error: String) = Unit   // startForeground failed after ServiceController.start() returned true
}
data class LogQuery(val start: Long? = null, val end: Long? = null, val level: LogLevel? = null, val limit: Int? = null, val ascending: Boolean = true)
interface LogStore : LogSink { fun configure(level: LogLevel, maxDays: Int); suspend fun read(q: LogQuery): String; suspend fun destroy()
  suspend fun upload(url: String, headers: Map<String, String>, paramsJson: String?): HttpResult; suspend fun prepareEmail(email: String, subject: String?): Intent }
```

**`Constants.kt` (S).**
- Prefs name `location_tracking_prefs`. Database `location_tracking.db`. Log directory `location-tracking-logs`.
- `NOTIFICATION_ID=7301`.
- PendingIntent request codes:

  | Use | Code |
  |---|---|
  | heartbeat | 7310 |
  | GMS activity | 7320 |
  | GMS geofence | 7321 |
  | HMS activity | 7330 |
  | HMS geofence | 7331 |
  | notification content | 7349 |
  | notification actions | 7350 + index |
  | Android proximity | 7400 (told apart by data URI `lt-geofence://<id>`) |

- Actions: `com.brickssoft.locationtracking.{HEARTBEAT,NOTIFICATION_ACTION,GEOFENCE,ACTIVITY}` and `EXTRA_ACTION_ID`.
- Helpers `piMutable()` (`FLAG_UPDATE_CURRENT|FLAG_MUTABLE` on API 31+) and `piImmutable()`.

**`Components.kt` (S)** is a lazy service locator.
- `Components.get(ctx)` is thread-safe and uses the application context.
- On first creation, `bootstrap()` sets `Logger.sink = logStore` and collects `configStore.config` into `logStore.configure(...)`.
- Tests use `@VisibleForTesting reset()`.

The wiring below fixes every unit's constructor signature:

```kotlin
val clock: Clock = SystemClockImpl(context); val dispatchers = AppDispatchers.DEFAULT
val scope = CoroutineScope(SupervisorJob() + dispatchers.engine + CoroutineExceptionHandler { _, t -> Logger.e("LT", "uncaught", t) })
val events: EventBus = SimpleEventBus(); val http: OkHttpClient by lazy { OkHttpClient() }
val logStore: LogStore by lazy { FileLogger(context, clock, dispatchers, lazy { http }) }                                  // U17
val configStore: ConfigStore by lazy { SharedPrefsConfigStore(context, clock) }                                           // U3
val permissions: PermissionManager by lazy { DefaultPermissionManager(context) }                                          // U15
val deviceSettings: DeviceSettings by lazy { DefaultDeviceSettings(context) }                                             // U15
val providers: ProviderFactory by lazy { DefaultProviderFactory(context, configStore) }                                   // U6
val device: DeviceMonitor by lazy { DefaultDeviceMonitor(context, configStore, permissions, lazy { providers }, events, clock, lazy { recordFactory }, lazy { recordSink }, scope) } // U14
val deviceInfo: DeviceInfoProvider by lazy { DefaultDeviceInfoProvider(context, lazy { providers }) }                     // U14
private val database by lazy { TrackingDatabase(context) }                                                                // U10
val locationStore: LocationStore by lazy { SqliteLocationStore(database, configStore, clock, dispatchers) }               // U10
val geofenceStore: GeofenceStore by lazy { SqliteGeofenceStore(database, dispatchers) }                                   // U10
val processor: LocationProcessor by lazy { DefaultLocationProcessor(configStore) }                                        // U9
val odometer: Odometer by lazy { DefaultOdometer(configStore) }                                                           // U9
val recordFactory: RecordFactory by lazy { DefaultRecordFactory(configStore, device, providers, clock) }                  // U9
val syncer: HttpSyncer by lazy { OkHttpSyncer(configStore, locationStore, device, events, clock, http, dispatchers, scope) } // U11
val heartbeat: HeartbeatScheduler by lazy { DefaultHeartbeatScheduler(context, configStore, device, providers, locationStore, recordFactory, lazy { recordSink }, clock, scope) } // U12
val recordSink: RecordSink by lazy { DefaultRecordSink(locationStore, configStore, heartbeat, syncer, events) }           // S
val geofences: GeofenceManager by lazy { DefaultGeofenceManager(geofenceStore, providers, configStore, recordFactory, recordSink, events, clock, scope) } // U13
val positions: PositionService by lazy { DefaultPositionService(providers, configStore, permissions, device, recordFactory, recordSink, clock, scope) } // U16
val serviceController: ServiceController by lazy { DefaultServiceController(context, configStore, events) }              // U8
val engine: TrackingEngine by lazy { DefaultTrackingEngine(configStore, providers, processor, odometer, recordFactory, recordSink,
    heartbeat, geofences, serviceController, device, syncer, permissions, events, clock, scope) }                         // U7
```

The dependency cycles (the record sink and the heartbeat scheduler; the device monitor, the record factory and the sink) are broken with `Lazy<T>`. Constructors must not touch lazy parameters.

**Test fakes (S, `testing/`).**
- Time and threads: `FakeClock(now, elapsed, bootCount){advance(ms)}`, `testDispatchers(scheduler)`.
- Events and config: `RecordingEventBus`; `FakeConfigStore` (MutableStateFlows; its JSON methods apply a minimal set of keys).
- Providers:
  - `FakeLocationBackend`: records specs and listeners; `emit(vararg loc)`; scripted last and current locations.
  - `FakeActivityBackend`, `FakeGeofenceBackend(supportsDwell)`, `FakeProviderFactory(kind, ...)`.
- Storage: `FakeLocationStore` and `FakeGeofenceStore` (in-memory).
- Records: `FakeRecordFactory` (uuids "rec-1..n", times from FakeClock); `FakeRecordSink` (collects records; optional passthrough to the store).
- Other components:
  - `FakeHttpSyncer`, `FakeHeartbeatScheduler`, `FakeGeofenceManager`;
  - `FakeDeviceMonitor` (mutable fields), `FakeDeviceSettings`, `FakePermissionManager`, `FakeServiceController`;
  - `FakeLocationProcessor` (accept-all or scripted), `FakeOdometer`, `FakeLogStore`, `FakePositionService`, `FakeTrackingEngine`.
- Builders: `Fixtures.location(...)`, `Fixtures.record(...)`, `Fixtures.circle(...)`, `Fixtures.polygon(...)`.

### Key cross-unit flows (behavioral contract)

- **`engine.start()`:**
  1. Require `permissions.hasForegroundLocation()`, otherwise throw `PERMISSION_DENIED`.
  2. `updateRuntime{enabled=true, mode=LOCATION, trackingStartedAt}`.
  3. `serviceController.start()`. On failure: set enabled=false, record `tracking_stop` with reason `permission_denied`, and throw `PERMISSION_DENIED`.
  4. Start `device.start()`, `syncer.start()`, `heartbeat.start()` and `geofences.onTrackingStarted(mode)`, then start the activity backend and the location request.
  5. Submit a `tracking_start` record with the last known location.
  6. Emit `EnabledChange(true)`.
  7. Fetch an initial fix and record `motionchange` with `is_moving=false` (asynchronously; `start()` has already resolved).
- **`engine.stop()`** is the reverse order. The `tracking_stop` record is submitted *before* `serviceController.stop()`.
- **`engine.restore(reason)`** (cold process with `runtime.enabled`; from `ready()`, the heartbeat receiver, `START_STICKY`, an activity update, or `BootReceiver` with `startOnBoot`): no foreground permission → stop with `permission_denied`; `serviceController.start()` refused → stop with `service_start_failed` (no retry on later alarms; the start is skipped when the service is already running, e.g. a `START_STICKY` restart); otherwise activate the session and record `tracking_start` with the reason. A running session whose service is gone restarts the service, or stops with `service_start_failed`.
- **Asynchronous service failure.** `LocationTrackingService.onStartCommand` reports a failed `startForeground` (for example a background start on Android 12+ without an exemption, or Android 14+ with only while-in-use location) through `engine.onServiceStartFailed(error)`, which stops a running or enabled session with `service_start_failed`. On Android 12+ a battery-exempt app (the exemption is itself an exemption from background-start restrictions), `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` may start the service from the background; the non-exempt backup heartbeat alarm (inexact, temporary allowance without foreground-service capability) cannot.
- **Geofence re-registration.** The engine subscribes to `ProviderChange` events during a session. When location becomes enabled again, or the permission level changes while enabled (and on the first event of a session), it calls `geofences.onTrackingStarted(mode)` again, because GMS/HMS drop geofences when location is switched off. Re-adding the same ids is idempotent.
- **Backend switch** (`locationProvider` changed): while tracking, remove location/activity updates and call `geofences.onTrackingStopped()` before `providers.reselect()`, then `geofences.onTrackingStarted(mode)` and the requests on the new backend; `device.checkProviderState("reselect")` if the kind changed.
- **`startGeofences()`** uses mode GEOFENCES. The foreground service and heartbeat keep running. There is no continuous location request, except while `geofences.needsContinuousLocation` is true.
- **Motion (U7).**
  - (Round 2 replaced the STATIONARY request, the exit rule and the `runtime.lastLocation` refresh below with the GPS-off mode of [docs/e2e/architecture.md §3](e2e/architecture.md#3-stationary-gps-off-mode).)
  - STATIONARY uses a balanced location request (or the configured accuracy if lower-power), at most one fix per minute; it becomes the configured (MOVING) request while `geofences.needsContinuousLocation`. It exits to MOVING when either happens:
    - a fix is more than `max(stationaryRadius, accuracy)` from the anchor;
    - a moving activity at or above the confidence threshold lasts for `motionTriggerDelay`.
  - STATIONARY fixes feed only the state machine and the geofences: no records, no odometer. `runtime.lastLocation` is refreshed from them at most every 10 s, so heartbeats carry a recent fix.
  - MOVING uses the configured request. Requests always use `distanceFilterM = 0`; the engine applies the elastic filter itself.
  - Stop detection (unless `disableStopDetection`): the `stopTimeout` timer is always armed while MOVING; evidence of motion (a displacement beyond `max(stationaryRadius, accuracy)` or a confident moving activity) restarts it, so STATIONARY follows `stopTimeout` after the last evidence of motion. `stopTimeout` has a floor of 1 min.
  - Elastic distance filter: `d = distanceFilter * max(1, round(speed/5.0) * elasticityMultiplier)`, unless `disableElasticity`.
  - `changePace` forces a transition (ignored while not tracking or in GEOFENCES mode).
  - `stopOnStationary` (automatic stop-timeout transitions only, not `changePace(false)`) and `stopAfterElapsedMinutes` call `stop()` with the matching reason.
  - Overdue timers are also checked after each fix batch, activity sample and `Heartbeat` event, because `delay()` does not advance in deep sleep.
  - Every accepted MOVING fix goes through `processor`, then `odometer`, then `geofences.onLocation`, then the distance check, then `recordFactory`, then `recordSink`.
- **Providerchange.** `DeviceMonitor` checks provider state on PROVIDERS_CHANGED / MODE_CHANGED (debounced 1 s; receivers registered by `device.start()` at session activation and kept for the process lifetime), on the plugin's `handleOnResume`, at session activation (start or restore), on each heartbeat, and after a backend reselect. The first observation is persisted silently; a later difference emits `ProviderChange` (also while tracking is off) and, while enabled, submits a `providerchange` record; the new state is persisted after the submit. Revoking a permission kills the process; the diff against the persisted `runtime.providerState` catches it on the next check.
- **Receivers** use `goAsync()` and `Components.get(ctx).scope.launch { ...; finish() }`, which requires the sinks to be suspend functions.

**Heartbeat algorithm (U12).**
- **Base time.** `base` is the last record time. Use the elapsed clock when the boot count matches, otherwise wall time. If there is no record, use a stable "now" anchor. A last record older than `runtime.trackingStartedAt` moves the base up to the session start (no heartbeat immediately at `start()`).
- **Due time.** `due = base + minInterval`, and never earlier than the last attempt + `minInterval` (a failed attempt does not loop). Always use `ELAPSED_REALTIME_WAKEUP`.
- **Scheduling:**
  - If `device.canScheduleExactAlarms()`: use `setExactAndAllowWhileIdle(due, PendingIntent)`. Strategy is `EXACT`.
  - Otherwise use two alarms:
    - `setExact(due, tag, OnAlarmListener, mainHandler)`;
    - a backup `setAndAllowWhileIdle(backupAt, PendingIntent)`, where `backupAt = due`, or `max(due, lastBackupFireElapsed + 9 min)` while `isDeviceIdleMode()`. The last backup fire (elapsed, boot count) is persisted, so pacing survives process restarts; it is ignored after a reboot.

    Strategy is `LISTENER_WITH_BACKUP` or `IDLE_PACED`.
  - Re-evaluate the schedule on DEVICE_IDLE_MODE_CHANGED, on config/enabled changes and on every record; re-arm only when the window changes (no cancel first: AlarmManager replaces the alarm).
  - The strategy and next time are persisted so `status()` in a new process (same boot) reports what was armed.
- **When any alarm fires** (serialized; a listener and a backup alarm for one window produce one heartbeat):
  - If `now - base >= minInterval - 1s`:
    1. Take a PARTIAL_WAKE_LOCK (60 s timeout).
    2. `device.checkProviderState("heartbeat")` (≤ 3 s).
    3. Location = `runtime.lastLocation ?: backend.getLastLocation()` (≤ 3 s).
    4. Re-check that the window is still due and tracking was not stopped (a record created meanwhile, e.g. a `providerchange`, supersedes the heartbeat).
    5. Submit a HEARTBEAT record. The sink reschedules the heartbeat and the syncer uploads the record immediately.
    6. (Receiver path) If tracking is enabled and the foreground service is not running, call `engine.restore("restore")` through `Components.get(ctx)`. If Android refuses the service, the engine stops with `service_start_failed`.
  - Otherwise, reschedule.
- **Errors.** Wrap `setExactAndAllowWhileIdle` in try/catch for `SecurityException`.

---

## 4. Plugin AndroidManifest.xml (SCAFFOLD)

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <uses-permission android:name="android.permission.INTERNET"/>
  <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
  <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>
  <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
  <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION"/>
  <uses-permission android:name="android.permission.ACTIVITY_RECOGNITION"/>
  <uses-permission android:name="com.google.android.gms.permission.ACTIVITY_RECOGNITION" android:maxSdkVersion="28"/>
  <uses-permission android:name="com.huawei.hms.permission.ACTIVITY_RECOGNITION" android:maxSdkVersion="28"/>
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION"/>
  <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
  <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
  <uses-permission android:name="android.permission.WAKE_LOCK"/>
  <!-- intentionally NOT declared: SCHEDULE_EXACT_ALARM, USE_EXACT_ALARM, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -->
  <uses-feature android:name="android.hardware.location.gps" android:required="false"/>
  <queries>
    <intent><action android:name="android.intent.action.SENDTO"/><data android:scheme="mailto"/></intent>
    <package android:name="com.huawei.systemmanager"/><package android:name="com.hihonor.systemmanager"/>
    <package android:name="com.miui.securitycenter"/><package android:name="com.miui.powerkeeper"/>
    <package android:name="com.coloros.safecenter"/><package android:name="com.oppo.safe"/><package android:name="com.coloros.oppoguardelf"/>
    <package android:name="com.iqoo.secure"/><package android:name="com.vivo.permissionmanager"/>
    <package android:name="com.samsung.android.lool"/><package android:name="com.samsung.android.sm"/>
    <package android:name="com.oneplus.security"/><package android:name="com.asus.mobilemanager"/>
    <package android:name="com.letv.android.letvsafe"/><package android:name="com.htc.pitroad"/>
    <package android:name="com.meizu.safe"/><package android:name="com.evenwell.powersaving.g3"/>
  </queries>
  <application>
    <service android:name="com.brickssoft.locationtracking.service.LocationTrackingService"
        android:exported="false" android:foregroundServiceType="location" android:stopWithTask="false"/>
    <receiver android:name="com.brickssoft.locationtracking.service.BootReceiver" android:exported="true">
      <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED"/>
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED"/>
        <action android:name="android.intent.action.QUICKBOOT_POWERON"/>
        <action android:name="com.htc.intent.action.QUICKBOOT_POWERON"/>
      </intent-filter>
    </receiver>
    <receiver android:name="com.brickssoft.locationtracking.heartbeat.HeartbeatAlarmReceiver" android:exported="false"/>
    <receiver android:name="com.brickssoft.locationtracking.service.NotificationActionReceiver" android:exported="false"/>
    <receiver android:name="com.brickssoft.locationtracking.provider.gms.GmsActivityReceiver" android:exported="false"/>
    <receiver android:name="com.brickssoft.locationtracking.provider.gms.GmsGeofenceReceiver" android:exported="false"/>
    <receiver android:name="com.brickssoft.locationtracking.provider.hms.HmsActivityReceiver" android:exported="false"/>
    <receiver android:name="com.brickssoft.locationtracking.provider.hms.HmsGeofenceReceiver" android:exported="false"/>
    <receiver android:name="com.brickssoft.locationtracking.provider.android.AndroidGeofenceReceiver" android:exported="false"/>
    <provider android:name="com.brickssoft.locationtracking.logging.LogFileProvider"
        android:authorities="${applicationId}.locationtracking.logs" android:exported="false" android:grantUriPermissions="true">
      <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/lt_file_paths"/>
    </provider>
  </application>
</manifest>
```

Use fully qualified class names. Use a `FileProvider` subclass, because the Capacitor app template already declares `androidx.core.content.FileProvider`; declaring that class again would cause a manifest-merge conflict.

---

## 5. Build files (SCAFFOLD)

**Repo root:**
- `package.json`:
  - name `@bricks-soft/capacitor-location-tracking`, `"private": true`, `"license": "UNLICENSED"`;
  - `main`/`module`/`types`/`unpkg` as in the Capacitor plugin template;
  - `files: ["android/src/main/","android/build.gradle","android/consumer-rules.pro","dist/"]`;
  - `"capacitor": {"android": {"src": "android"}}` with no ios key;
  - scripts: `clean`, `build` (`npm run clean && tsc && rollup -c rollup.config.mjs`, with **no docgen** because docgen rewrites the README), `test` (`node --test "test/**/*.mjs"`; Node 22 treats a bare `test/` argument as a module path, not a directory), and `docgen` (`docgen --api LocationTrackingPlugin --project tsconfig.docgen.json --output-readme README.md --output-json dist/docs.json`; run it by hand after changing `definitions.ts`);
  - devDependencies: `@capacitor/core@8.5.2`, `@capacitor/android@8.5.2`, `@capacitor/docgen@^0.3.1`, `typescript@~5.9.3`, `rollup@^4.53`, `rimraf@^6`;
  - peerDependency `@capacitor/core >=8.0.0`.
- `tsconfig.json` and `rollup.config.mjs` as in the template (IIFE name `capacitorLocationTracking`).
- `tsconfig.docgen.json`: used only by `@capacitor/docgen` 0.3.1, which bundles TypeScript 4.2. It sets an explicit `lib: ["lib.es2017.d.ts"]` (no DOM lib), so return types resolve (otherwise every method renders as `Promise<any>`) and the plugin's `Location`/`PermissionStatus` are not shadowed by the DOM types.
- `ios/README.md`: placeholder only.
- `.gitignore`: `node_modules`, `dist`, `android/build`, `android/.gradle`, `**/local.properties`, `example/node_modules`, `example/www/vendor`, `example/android/{.gradle,build,app/build,app/src/main/assets/public,capacitor-cordova-android-plugins}`.

**`android/settings.gradle`:**

```groovy
rootProject.name = 'bricks-soft-capacitor-location-tracking'
include ':capacitor-android'
project(':capacitor-android').projectDir = new File('../node_modules/@capacitor/android/capacitor')
```

**`android/build.gradle`:**
- Kotlin/AGP buildscript as in the Capacitor plugin template.
- `resourcePrefix 'lt_'`.
- `buildConfigField`s `PLUGIN_VERSION` and `PACKAGED_PROVIDERS`.
- GMS and HMS as `compileOnly` and `testImplementation`, plus `implementation` driven by the property:

```groovy
def ltProviders = (project.findProperty('locationTracking.providers') ?: 'gms').toString()
    .split(',').collect { it.trim().toLowerCase() }.findAll { it && it != 'none' }.toSet()
...
compileOnly "com.google.android.gms:play-services-location:$playServicesLocationVersion"
compileOnly "com.huawei.hms:location:$hmsLocationVersion"
if (ltProviders.contains('gms')) implementation "com.google.android.gms:play-services-location:$playServicesLocationVersion"
if (ltProviders.contains('hms')) implementation "com.huawei.hms:location:$hmsLocationVersion"
```

- Repositories: `google()`, `mavenCentral()`, `maven { url = 'https://developer.huawei.com/repo/' }`.
- Unit tests: `includeAndroidResources = true`, `returnDefaultValues = true`, `maxHeapSize = '1024m'`, `maxParallelForks = 1`.

**`android/consumer-rules.pro`:**
- `-dontwarn` for `com.google.android.gms.**` and `com.huawei.**`.
- Keep the `*ProviderBundle(Context)` constructors.
- `-keepnames` for the SDK classes `DefaultProviderFactory` probes with `Class.forName`: `com.google.android.gms.location.LocationServices`, `com.google.android.gms.common.GoogleApiAvailability`, `com.huawei.hms.location.LocationServices` (otherwise R8 may rename them and a minified app silently falls back to Android).
- Huawei's recommended keeps: `com.huawei.hms.**`, `com.huawei.hianalytics.**`, `com.huawei.updatesdk.**`.

**`android/gradle.properties`.** The same file is copied to `example/android/gradle.properties`, plus `locationTracking.providers=gms,hms` there. These settings are tuned for many concurrent builds on a 4-CPU, 15 GB machine:

```
org.gradle.jvmargs=-Xmx1280m -XX:MaxMetaspaceSize=512m -XX:+UseParallelGC -Dfile.encoding=UTF-8
org.gradle.daemon.idletimeout=180000
org.gradle.parallel=false
org.gradle.workers.max=2
org.gradle.caching=true
org.gradle.vfs.watch=false
org.gradle.configuration-cache=false
kotlin.compiler.execution.strategy=in-process
kotlin.incremental=true
android.useAndroidX=true
android.nonTransitiveRClass=true
```

**`scripts/gradle-slot.sh`** allows at most 3 concurrent Gradle builds on the machine, using flock slots. Workers call the system `gradle` (8.14.3) through this script. The Gradle wrapper (8.14.3) is included for end users.

**Example app (`example/`):**
- The Android skeleton is scaffold-owned; U18 owns `www/**`.
- Dependencies: `"@bricks-soft/capacitor-location-tracking": "file:.."`, `@capacitor/core`, `@capacitor/android`, `@capacitor/cli` (all 8.5.2).
- Scripts:
  - `copy-vendor`: copies `capacitor.js` and the plugin's `dist/plugin.js` into `www/vendor/`;
  - `sync`: `npm run copy-vendor && cap sync android`.
- `capacitor.config.json`: appId `com.brickssoft.locationtracking.example`.
- `www/index.html` loads `vendor/capacitor.js`, `vendor/plugin.js` and `app.js`, and uses the global `capacitorLocationTracking.LocationTracking`. There is no bundler.
- `example/android/` is generated once with `npx cap add android` and committed. It adds the Huawei maven repo to both `buildscript.repositories` and `allprojects.repositories`.

**Build sequence:**
1. At the repo root: `npm ci && npm run build`.
2. In `example/`: `npm ci && npm run sync`.
3. Then `cd android && ../../scripts/gradle-slot.sh assembleDebug -PlocationTracking.providers=gms,hms`.

---

## 6. Units

Units can be merged in any order, because each one replaces only its own stubs.

| # | Unit | Owns | Focus |
|---|---|---|---|
| 1 | TS wrapper + web | `src/plugin.ts, index.ts, events.ts, web.ts, src/web/**`, `test/*.mjs` | `node --test` tests on the compiled web stub |
| 2 | Android bridge | `LocationTrackingPlugin.kt`, `bridge/**` | All `@PluginMethod`s. Option parsing. `TrackingException` mapped to `call.reject(msg, code.name)`. EventBus events forwarded with `notifyListeners(EventJson.name, JSObject.fromJSONObject(EventJson.payload))`. `PluginPermissionHost`. `WatchRegistry` (`RETURN_CALLBACK`, `setKeepAlive(true)`, released on clearWatch and `handleOnDestroy`). The NOT_READY rule. `handleOnResume` calls `device.checkProviderState("resume")`. Logic lives in `PluginHandlers` (JSONObject in and out) so Robolectric can test it. |
| 3 | Config + state | `config/ConfigJson.kt, SharedPrefsConfigStore.kt, ConfigValidator.kt` | Deep merge; null resets to default; ready/reset semantics. Clamps: minInterval ≥ 60, max ≥ min, confidence 0–100, maxBatchSize ≥ 1, at most 3 actions. `stateToJson`. |
| 4 | GMS | `provider/gms/**` | Bundle (`GoogleApiAvailability`); fused location (multi-listener); `requestActivityUpdates` + receiver + `ActivityRecognitionResult`; GeofencingClient + receiver; own `Task.await()`; injectable clients; MockK tests |
| 5 | HMS | `provider/hms/**` | Same as unit 4 with `HuaweiApiAvailability`, `ActivityIdentificationService`/`ActivityIdentificationResponse`, `GeofenceService`/`GeofenceData`; hmf Task await |
| 6 | Android fallback + factory | `provider/android/**`, `provider/DefaultProviderFactory.kt` | `LocationManagerCompat` updates and current location. Activity backend with `isSupported=false`. Proximity-alert geofences (`supportsDwell=false`, one PendingIntent per id via data URI). Reflective selection with `Class.forName(name, false, loader)`. |
| 7 | Engine | `engine/**` (except the contract) | State machine, timers via `Clock` and `scope`, elastic filter, audit records, restore/boot/terminate, provider reselect on `setConfig`, reacting to `needsContinuousLocation` |
| 8 | Service | `service/**` (except the contract) | `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_LOCATION)` immediately in `onStartCommand`; `START_STICKY`; `onTaskRemoved` calls `engine.onTerminate()`; notification (icon resolution, color, priority mapped to channel importance, actions, content intent), refreshed on config change; `BootReceiver` calls `engine.restore("boot"/"package_replaced")` if `startOnBoot`, else sets `enabled=false` |
| 9 | Processing | `processing/**` | Accuracy, implied-speed, identical and mock filters; 1-D Kalman on lat/lng; odometer with accuracy gate; RecordFactory (uuid v4, clock and boot metadata, battery via `device`, backend) |
| 10 | SQLite | `data/**` | SQLiteOpenHelper v1 with `records(uuid PK, event, recorded_at, json, attempts, last_attempt_at)` and `geofences(id PK, json, runtime_json)`; RecordJson for rows; pruning; all work on `dispatchers.io` |
| 11 | HTTP | `http/**` | Sync policy; BodyBuilder (rootProperty, params, batch, sent_at); templates; JWT; Http and Authorization events; MockWebServer tests |
| 12 | Heartbeat | `heartbeat/**` | The algorithm in §3. Robolectric `ShadowAlarmManager` (`setCanScheduleExactAlarms`) and `ShadowPowerManager.setIsDeviceIdleMode/setIgnoringBatteryOptimizations`. Pure `HeartbeatWindow` tests. |
| 13 | Geofences | `geofence/**` | Validation (at most 100; a polygon needs at least 3 vertices); enclosing circle; ray-cast point-in-polygon; dwell synthesis; geofence records and events; GeofencesChange; `initialTriggerEntry` |
| 14 | Device | `device/**` | Receivers and NetworkCallback via `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`; ProviderState (permission level, approximate); providerchange diff and record; Connectivity and PowerSave events; battery; idle, battery-exempt and exact-alarm checks; DeviceInfo; Sensors |
| 15 | Permissions + settings | `permission/**`, `settings/**` | API-aware status. Request order: background last; on API 30+ show the rationale AlertDialog, then request; below API 29 background is implied. Battery-optimization list intent. OEM intents table with `resolveActivity` fallbacks. App and location settings. |
| 16 | Positions | `position/**` | `maximumAge` short-circuit; N samples (best accuracy) within the timeout; mock rejection; persist via the sink; watch registry over backend listeners |
| 17 | Logger | `logging/**` (except the contract) | Daily files; level filter; `logMaxDays` purge; query; destroy; multipart upload via OkHttp; gzip into `cacheDir` + FileProvider `ACTION_SEND` intent |
| 18 | Example + docs | `example/www/**`, `README.md`, `docs/**` (except this file), `CHANGELOG.md` | UI for every method and event; docgen; wire-format doc; OEM and heartbeat caveats; manual on-device test checklist |

---

## 7. Risks and gotchas

1. **Foreground service on Android 12–15.**
   - `start()` must be called while the app is visible.
   - `startForeground` must be called within about 5 s, even if the service is about to stop; otherwise Android throws `ForegroundServiceDidNotStartInTimeException`.
   - The location type needs a granted runtime location permission and `FOREGROUND_SERVICE_LOCATION`.
   - Starts from the background rely on exemptions (BOOT_COMPLETED, MY_PACKAGE_REPLACED, exact-alarm delivery). In practice they require `ACCESS_BACKGROUND_LOCATION`, because of the while-in-use restriction.
   - To catch the start failure, catch `Exception` and check `SDK_INT>=31 && e is ForegroundServiceStartNotAllowedException`. Don't use that class in a catch clause: it doesn't exist below API 31.
   - Android 15's boot restriction does not cover the location type, but the app must have been launched once after install.
2. **Alarms.**
   - `setExactAndAllowWhileIdle` throws `SecurityException` unless `canScheduleExactAlarms()`; re-check before every call.
   - Listener alarms die with the process, and deep idle defers them.
   - While idle, allow-while-idle alarms are rate-limited; keep them at least 9 min apart. So when the app is not battery-exempt, heartbeats during deep Doze are about 9 min apart. This limitation is documented and shows in `getHeartbeatStatus().strategy`.
   - Use `ELAPSED_REALTIME_WAKEUP`, so wall-clock changes don't matter.
3. **PendingIntent mutability.**
   - GMS/HMS activity and geofence PendingIntents and proximity alerts need `FLAG_MUTABLE` (API 31+).
   - Alarm, notification and action PendingIntents use `FLAG_IMMUTABLE`.
   - Always use explicit component intents, because Android 14 rejects mutable implicit PendingIntents.
4. **compileOnly class loading.**
   - Never reference `com.google.*` or `com.huawei.*` types outside `provider/gms` and `provider/hms`. This includes constants, `is` checks, default parameters, and companion objects in shared code.
   - Load bundles only through `Class.forName`, after checking that the SDK class is present.
   - The GMS/HMS receivers are declared in the manifest, but they only fire if we registered a PendingIntent.
5. **HMS setup.**
   - The app must add the Huawei maven repo to its own repositories.
   - At runtime HMS needs the HMS Core APK and an AppGallery Connect app with the signing certificate's SHA-256 registered.
   - `agconnect-services.json` plus the `com.huawei.agconnect` plugin are recommended. Without them, expect 907135xxx or 6003 errors.
   - Below API 29 the activity permission is `com.huawei.hms.permission.ACTIVITY_RECOGNITION`.
   - If HMS AAR manifests or init providers misbehave under Robolectric, test the adapters with MockK and injected client factories.
6. **Permissions.**
   - On API 30+, background location must be requested separately, after foreground location. The "request" opens Settings.
   - On API 29, "Allow all the time" appears in the dialog. Below API 29 there is no background permission.
   - Capacitor reports aliases for permissions that don't exist on the device's API level as denied. U15 maps these: background below 29 equals foreground; activity recognition below 29 and notifications below 33 count as granted.
   - The user can grant only approximate location (Android 12).
   - `start()` needs only foreground permission, because the foreground service carries while-in-use access. Geofencing and restart after boot need background permission.
7. **Capacitor.**
   - `PluginCall` and `Bridge` are hard to construct in tests. Keep the plugin thin, test `PluginHandlers` with JSONObject, and use `mockk<PluginCall>(relaxed = true)` only if unavoidable.
   - Events are lost when no WebView is attached; that's expected.
   - Watch calls need `setKeepAlive(true)` and must be released.
   - `@PermissionCallback` methods are resolved by name through reflection.
8. **Robolectric and JVM tests.**
   - Default `sdk=34`; use only the pre-downloaded SDK levels.
   - The main looper is PAUSED, so call `shadowOf(getMainLooper()).idle()`.
   - Inject a `TestDispatcher` and use `runTest`. Call `Dispatchers.setMain` if the code touches Main.
   - `org.json` is stubbed in plain JVM tests (`returnDefaultValues`), so any test that uses JSON must be a Robolectric test.
   - Compare parsed JSON, not strings.
   - Call `Components.reset()` and set `Logger.sink = null` in `@After`.
9. **minSdk 24.**
   - No `java.time`, `Instant` or `Duration`; use `Iso8601`.
   - `Location.isMock` needs API 31+; below that use `isFromMockProvider`.
   - Use `LocationManagerCompat.getCurrentLocation` for older APIs.
10. **Notifications.**
    - A channel's importance cannot change after creation.
    - A wrong or bitmap small icon shows as a white square; fall back to `lt_ic_notification`.
    - If POST_NOTIFICATIONS is denied (API 33+), the foreground service still runs but the notification is hidden.
11. **Phone makers' task killers.** `START_STICKY` plus the heartbeat PendingIntent alarm are how tracking comes back. The receiver calls `engine.restore("restore")`, which emits `tracking_start` with reason `restore`. On Android 12+ that works only when Android allows the background foreground-service start (exact alarm of a battery-exempt app); otherwise the engine records `tracking_stop` with reason `service_start_failed` and stays stopped until the app calls `start()` again. The OEM settings screens are best-effort; treat `ActivityNotFoundException` and `SecurityException` as `opened:false`.
12. **Threading.**
    - Engine state changes only on `dispatchers.engine`. SQLite and HTTP run only on `io`.
    - `LocationManager` requests need a Looper; use the main one.
    - Receivers use `goAsync()` and must finish within about 10 s. Heartbeat uploads also hold their own wake lock.
13. **Network in Doze** relies on the foreground service's process state. Uploads while tracking is off (no foreground service) may wait until a maintenance window.
14. **`Settings.Global.BOOT_COUNT`** may be missing; use -1.
15. **Repo hygiene.**
    - `file:..` creates a symlink cycle (`example/node_modules/@bricks-soft/...` points back to the repo root). Never include `example/` in the root `tsconfig` or `files`.
    - Don't run `docgen` in `build`.
    - Never commit `local.properties`, build directories, or `example/android/app/src/main/assets/public`.
16. **Library resources** merge into the app's namespace; the `lt_` prefix prevents silent overrides.
17. **Activity recognition** often stops delivering updates after a long still period. Leaving the stationary state must also work from location distance (`stationaryRadius`); the heartbeat covers liveness.
