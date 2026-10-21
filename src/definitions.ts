import type { PermissionState, PluginListenerHandle } from '@capacitor/core';

export type CallbackID = string;
export type DesiredAccuracy = 'high' | 'balanced' | 'low' | 'passive';
export type LocationProviderSetting = 'auto' | 'gms' | 'hms' | 'android';
export type LocationBackend = 'gms' | 'hms' | 'android' | 'web';
export type TrackingMode = 'location' | 'geofences';
export type LogLevel = 'off' | 'error' | 'warn' | 'info' | 'debug' | 'verbose';
export type ActivityType = 'still' | 'on_foot' | 'walking' | 'running' | 'on_bicycle' | 'in_vehicle' | 'unknown';
export type RecordEvent =
  | 'location'
  | 'motionchange'
  | 'current_position'
  | 'watch_position'
  | 'heartbeat'
  | 'geofence'
  | 'tracking_start'
  | 'tracking_stop'
  | 'providerchange';
export type GeofenceAction = 'ENTER' | 'EXIT' | 'DWELL';
export type HttpMethod = 'POST' | 'PUT' | 'PATCH';
export type NotificationPriority = 'min' | 'low' | 'default' | 'high' | 'max';
export type PermissionType = 'location' | 'backgroundLocation' | 'activityRecognition' | 'notifications';
export type ConnectivityType = 'wifi' | 'cellular' | 'ethernet' | 'other' | 'none';
export type HeartbeatStrategy = 'exact' | 'listener_with_backup' | 'idle_paced' | 'disabled';
/** Rejection `code` of every failed promise. */
export type ErrorCode =
  | 'NOT_READY'
  | 'PERMISSION_DENIED'
  | 'LOCATION_DISABLED'
  | 'TIMEOUT'
  | 'UNAVAILABLE'
  | 'INVALID_ARGUMENT'
  | 'NOT_FOUND'
  | 'NO_URL'
  | 'HTTP_ERROR'
  | 'NETWORK_ERROR'
  | 'TOO_MANY_GEOFENCES'
  | 'NO_ACTIVITY'
  | 'IO_ERROR'
  | 'UNIMPLEMENTED'
  | 'INTERNAL';

/* ================= CONFIG (all optional; defaults shown) ================= */

export interface LocationFilterConfig {
  /** @default false */
  useKalman?: boolean;
  /**
   * Reject fixes with accuracy worse than this (m).
   * @default 100
   */
  trackingAccuracyThreshold?: number;
  /**
   * Reject fixes implying speed above this (m/s); 0 = off.
   * @default 80
   */
  maxImpliedSpeed?: number;
  /**
   * Odometer ignores fixes worse than this (m).
   * @default 20
   */
  odometerAccuracyThreshold?: number;
  /** @default false */
  allowIdenticalLocations?: boolean;
  /**
   * Drop mock fixes entirely (otherwise they are kept with mock:true).
   * @default false
   */
  rejectMockLocations?: boolean;
}

export interface GeolocationConfig {
  /** @default 'high' */
  desiredAccuracy?: DesiredAccuracy;
  /**
   * meters
   * @default 10
   */
  distanceFilter?: number;
  /**
   * ms
   * @default 1000
   */
  locationUpdateInterval?: number;
  /**
   * ms
   * @default 500
   */
  fastestLocationUpdateInterval?: number;
  /** @default false */
  disableElasticity?: boolean;
  /** @default 1 */
  elasticityMultiplier?: number;
  /**
   * meters
   * @default 25
   */
  stationaryRadius?: number;
  /**
   * minutes
   * @default 5
   */
  stopTimeout?: number;
  /**
   * minutes, 0 = off
   * @default 0
   */
  stopAfterElapsedMinutes?: number;
  /** @default false */
  stopOnStationary?: boolean;
  /**
   * default getCurrentPosition timeout, ms
   * @default 30000
   */
  locationTimeout?: number;
  filter?: LocationFilterConfig;
}

export interface ActivityConfig {
  /** @default false */
  disableMotionActivityUpdates?: boolean;
  /**
   * ms
   * @default 10000
   */
  activityRecognitionInterval?: number;
  /**
   * 0-100
   * @default 75
   */
  minimumActivityRecognitionConfidence?: number;
  /**
   * ms
   * @default 0
   */
  motionTriggerDelay?: number;
  /** @default false */
  disableStopDetection?: boolean;
}

export interface HeartbeatConfig {
  /** @default true */
  enabled?: boolean;
  /**
   * seconds, min 60
   * @default 180
   */
  minInterval?: number;
  /**
   * seconds, >= minInterval
   * @default 300
   */
  maxInterval?: number;
  /**
   * seconds, 0..60. After a heartbeat is queued, keep the phone awake until its upload has finished, or at most this
   * long, so it reaches the server on time while tracking is on. 0 = do not keep the phone awake.
   * @default 10
   */
  uploadKeepAwake?: number;
}

export interface AuthorizationConfig {
  /** @default 'JWT' */
  strategy?: 'JWT';
  accessToken?: string;
  refreshToken?: string;
  refreshUrl?: string;
  /**
   * String values may contain `{refreshToken}`.
   * @default {}
   */
  refreshPayload?: Record<string, string>;
  refreshHeaders?: Record<string, string>;
  /** @default 'json' */
  refreshPayloadEncoding?: 'json' | 'form';
  /**
   * access-token expiry, epoch ms; -1 = unknown
   * @default -1
   */
  expires?: number;
}

export interface HttpConfig {
  /** No url => nothing is uploaded (records stay queued). */
  url?: string | null;
  /** @default 'POST' */
  method?: HttpMethod;
  /** @default {} */
  headers?: Record<string, string>;
  /**
   * merged into the ROOT of every request body
   * @default {}
   */
  params?: Record<string, unknown>;
  /** @default true */
  autoSync?: boolean;
  /**
   * upload when queue >= threshold; 0 = every record
   * @default 0
   */
  autoSyncThreshold?: number;
  /**
   * s; upload normal records once the oldest queued one is this old; 0 = off
   * @default 0
   */
  syncInterval?: number;
  /** @default false */
  batchSync?: boolean;
  /** @default 100 */
  maxBatchSize?: number;
  /** @default false */
  disableAutoSyncOnCellular?: boolean;
  /**
   * '.' = no wrapping
   * @default 'location'
   */
  rootProperty?: string;
  /** JSON text with `<%= name %>` placeholders */
  locationTemplate?: string | null;
  /** used for event 'geofence'; falls back to locationTemplate */
  geofenceTemplate?: string | null;
  /**
   * ms
   * @default 60000
   */
  timeout?: number;
  /**
   * seconds, >= 0. The longest wait that a `Retry-After` header of a 429 or 503 answer can impose on automatic uploads.
   * @default 3600
   */
  maxRetryAfter?: number;
  authorization?: AuthorizationConfig | null;
}

export interface PersistenceConfig {
  /** @default 7 */
  maxDaysToPersist?: number;
  /**
   * -1 = unlimited
   * @default -1
   */
  maxRecordsToPersist?: number;
  /**
   * merged into `extras` of every record at creation time
   * @default {}
   */
  extras?: Record<string, unknown>;
}

export interface AppConfig {
  /** @default true */
  stopOnTerminate?: boolean;
  /** @default false */
  startOnBoot?: boolean;
}

export interface NotificationActionButton {
  id: string;
  label: string;
}

export interface NotificationConfig {
  /** @default app label */
  title?: string;
  /** @default 'Location tracking is active' */
  text?: string;
  /**
   * 'drawable/name' | 'mipmap/name'
   * @default plugin icon 'drawable/lt_ic_notification'
   */
  smallIcon?: string;
  largeIcon?: string;
  /** '#RRGGBB' */
  color?: string;
  /** @default 'default' */
  priority?: NotificationPriority;
  /** @default 'location_tracking' */
  channelId?: string;
  /** @default 'Location tracking' */
  channelName?: string;
  /** max 3; tap => 'notificationaction' event */
  actions?: NotificationActionButton[];
  /** notification that resumes tracking when Android refuses to restore it from the background (Android 14+) */
  resume?: ResumeNotificationConfig;
}

/**
 * Posted when Android refuses to restore tracking from the background (after a reboot, an app update or a process
 * restart), typically on Android 14+ without "Allow all the time". A tap resumes the session
 * (`tracking_start` reason `resume_notification`). Uses the tracking notification's small icon and color.
 */
export interface ResumeNotificationConfig {
  /** @default false */
  enabled?: boolean;
  /** @default app label */
  title?: string;
  /** @default 'Location tracking is paused. Tap to resume.' */
  text?: string;
  /** @default 'Paused location tracking' */
  channelName?: string;
}

export interface GeofenceConfig {
  /** @default true */
  initialTriggerEntry?: boolean;
}

export interface LoggerConfig {
  /** @default 'info' */
  logLevel?: LogLevel;
  /** @default 3 */
  logMaxDays?: number;
}

export interface BackgroundPermissionRationale {
  /** defaults from plugin string resources */
  title?: string;
  message?: string;
  positiveAction?: string;
  negativeAction?: string;
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
  /**
   * Android only
   * @default 'auto'
   */
  locationProvider?: LocationProviderSetting;
}

export interface ReadyOptions {
  config?: Config;
  /**
   * true: config = defaults + given config (every launch). false: given config applied only on the very first
   * ready(); afterwards persisted config wins.
   * @default true
   */
  reset?: boolean;
}

export interface State {
  enabled: boolean;
  trackingMode: TrackingMode;
  isMoving: boolean;
  odometer: number;
  backend: LocationBackend;
  lastRecordAt: string | null;
  /** reason of the last `tracking_stop` record (the `tracking_stop` reasons of `Location.reason`); null if none */
  lastStopReason: string | null;
  /** when tracking stopped for `lastStopReason` (ISO-8601 UTC ms); null if none */
  lastStopAt: string | null;
  /**
   * fully populated with defaults. On Android the `http.authorization` secrets (`accessToken`, `refreshToken`, the
   * values of `refreshPayload` and `refreshHeaders`) are masked: `***`, or `***` plus the last 4 characters for values
   * longer than 8 characters; a `refreshPayload` value that contains `{refreshToken}` is shown. Passing a masked value
   * back to `setConfig` keeps the stored one. The web stub returns them as given.
   */
  config: Config;
  /**
   * `ready()`, `setConfig()` and `reset()` only: what the given config contains that was accepted but is probably
   * wrong (for example an `http://` upload URL).
   */
  warnings?: string[];
}

/* ================= RECORDS (snake_case = identical to wire format) ================= */

export interface Coords {
  latitude: number;
  longitude: number;
  accuracy: number;
  altitude: number | null;
  altitude_accuracy: number | null;
  speed: number | null;
  speed_accuracy: number | null;
  heading: number | null;
  heading_accuracy: number | null;
}

export interface ProviderState {
  enabled: boolean;
  gps: boolean;
  network: boolean;
  permission: 'always' | 'when_in_use' | 'denied';
  accuracy: 'precise' | 'approximate' | 'none';
  backend: LocationBackend;
}

export interface Location {
  uuid: string;
  event: RecordEvent;
  /** fix time (ISO-8601 UTC ms); null if no location ever known */
  timestamp: string | null;
  /** record creation time */
  recorded_at: string;
  /** only present in HTTP bodies */
  sent_at?: string;
  elapsed_realtime_ms: number;
  /** -1 if unavailable */
  boot_count: number;
  is_moving: boolean;
  odometer: number;
  mock: boolean;
  coords: Coords | null;
  activity: { type: ActivityType; confidence: number };
  /**
   * level 0..1, -1 unknown. Heartbeat records also carry `temperature` (°C), `health` and `plugged` when the device
   * reports them.
   */
  battery: {
    level: number;
    is_charging: boolean;
    temperature?: number;
    health?: 'good' | 'overheat' | 'dead' | 'over_voltage' | 'cold' | 'failure' | 'unknown';
    plugged?: 'ac' | 'usb' | 'wireless' | 'dock' | 'none' | 'other';
  };
  backend: LocationBackend | null;
  extras?: Record<string, unknown>;
  /** event 'geofence' only */
  geofence?: { identifier: string; action: GeofenceAction; extras?: Record<string, unknown> };
  /** provider state when the record was created; the new state for event 'providerchange' */
  provider?: ProviderState;
  /**
   * tracking_start: start|start_geofences|boot|restore|package_replaced|resume_notification;
   * tracking_stop: stop|stop_on_stationary|stop_after_elapsed|terminate|permission_denied|service_start_failed|reboot|package_replaced|user_stopped
   */
  reason?: string;
  /** event 'tracking_stop' with reason 'user_stopped' only: when the user stopped the app (ISO-8601 UTC ms) */
  stopped_at?: string;
  /** event 'heartbeat' only, optional: how the heartbeat is scheduled */
  heartbeat?: HeartbeatMeta;
  /** connection type when the record was created */
  network?: ConnectivityType;
  /** events 'heartbeat' and 'tracking_start' only: device readings (see `DeviceReadings`) */
  device?: DeviceReadings;
}

/**
 * The `device` object of records; a reading is present only when the device provides it.
 * Heartbeat records: `background_restricted`, `standby_bucket`, `disk_free_bytes`, `thermal_status`.
 * `tracking_start` records: `manufacturer`, `model`, `os_version`, `sdk_int`, `android_id`, `low_ram_device`,
 * `ram_total_bytes`.
 */
export interface DeviceReadings {
  /** the user set the app's battery use to "Restricted" (Android 9+) */
  background_restricted?: boolean;
  /** App Standby bucket (Android 9+) */
  standby_bucket?: 'exempted' | 'active' | 'working_set' | 'frequent' | 'rare' | 'restricted';
  /** free space on the storage that holds the app's files */
  disk_free_bytes?: number;
  /** Android 10+ */
  thermal_status?: 'none' | 'light' | 'moderate' | 'severe' | 'critical' | 'emergency' | 'shutdown' | 'unknown';
  manufacturer?: string;
  model?: string;
  /** Android version, e.g. "14" */
  os_version?: string;
  sdk_int?: number;
  /** `Settings.Secure.ANDROID_ID`: declare "Device or other IDs" in Google Play's Data safety form */
  android_id?: string;
  low_ram_device?: boolean;
  ram_total_bytes?: number;
}

/** Scheduling metadata of a `heartbeat` record (snake_case = wire format). */
export interface HeartbeatMeta {
  strategy: 'exact' | 'listener_with_backup' | 'idle_paced';
  /** heartbeat.minInterval (s) when the heartbeat was created */
  min_interval: number;
  /** heartbeat.maxInterval (s) when the heartbeat was created */
  max_interval: number;
  /** when the next heartbeat is expected (ISO-8601 UTC ms); null if unknown */
  next_at: string | null;
  /** the app is exempt from battery optimization */
  battery_exempt: boolean;
  /** the device was in deep Doze */
  device_idle: boolean;
}

export type LocationRecord = Location;

export interface InsertLocationInput {
  coords: Pick<Coords, 'latitude' | 'longitude'> & Partial<Coords>;
  timestamp?: string;
  event?: RecordEvent;
  is_moving?: boolean;
  extras?: Record<string, unknown>;
}

/* ================= GEOFENCES ================= */

/**
 * Either (latitude, longitude, radius) = circle, or vertices = polygon. For polygons, getGeofences() also returns
 * the computed enclosing circle.
 */
export interface Geofence {
  identifier: string;
  latitude?: number;
  longitude?: number;
  /** m */
  radius?: number;
  /** [[lat,lng], ...] >= 3 points */
  vertices?: [number, number][];
  /** @default true */
  notifyOnEntry?: boolean;
  /** @default true */
  notifyOnExit?: boolean;
  /** @default false */
  notifyOnDwell?: boolean;
  /**
   * ms
   * @default 30000
   */
  loiteringDelay?: number;
  extras?: Record<string, unknown>;
}

/* ================= EVENTS ================= */

export interface MotionChangeEvent {
  isMoving: boolean;
  location: Location;
}
export interface ActivityChangeEvent {
  activity: ActivityType;
  confidence: number;
}
export interface HeartbeatEvent {
  location: Location;
}
export interface GeofenceEvent {
  identifier: string;
  action: GeofenceAction;
  location: Location;
  extras?: Record<string, unknown>;
}
export interface GeofencesChangeEvent {
  on: Geofence[];
  off: string[];
}
export interface HttpEvent {
  success: boolean;
  status: number;
  responseText: string;
  uuids: string[];
}
export interface ConnectivityChangeEvent {
  connected: boolean;
  type: ConnectivityType;
}
export interface PowerSaveChangeEvent {
  isPowerSaveMode: boolean;
}
export interface EnabledChangeEvent {
  enabled: boolean;
  /**
   * why: a `tracking_start` reason when tracking was switched on (start, start_geofences, resume_notification), a
   * `tracking_stop` reason when it was switched off; the same values as `Location.reason`
   */
  reason: string | null;
}
/** The location request failed (no permission, Play services or HMS Core refused it, no usable provider). */
export interface LocationErrorEvent {
  code: ErrorCode;
  message: string;
  backend: LocationBackend;
  /** the plugin requests location updates again after this many ms; null if it does not */
  retryInMs: number | null;
}
/** A record could not be stored because the device storage is full. */
export interface StorageFullEvent {
  uuid: string;
  event: RecordEvent;
  diskFreeBytes: number | null;
}
export interface NotificationActionEvent {
  id: string;
}
export interface AuthorizationEvent {
  success: boolean;
  status: number;
  error?: string;
  response?: Record<string, unknown>;
}
export interface LocationTrackingEventMap {
  location: Location;
  motionchange: MotionChangeEvent;
  activitychange: ActivityChangeEvent;
  providerchange: ProviderState;
  heartbeat: HeartbeatEvent;
  geofence: GeofenceEvent;
  geofenceschange: GeofencesChangeEvent;
  http: HttpEvent;
  connectivitychange: ConnectivityChangeEvent;
  powersavechange: PowerSaveChangeEvent;
  enabledchange: EnabledChangeEvent;
  notificationaction: NotificationActionEvent;
  authorization: AuthorizationEvent;
  locationerror: LocationErrorEvent;
  storagefull: StorageFullEvent;
}
export type LocationTrackingEventName = keyof LocationTrackingEventMap;

/* ================= MISC ================= */

export interface CurrentPositionOptions {
  /** @default 3 */
  samples?: number;
  /**
   * ms
   * @default geolocation.locationTimeout
   */
  timeout?: number;
  /**
   * ms
   * @default 0
   */
  maximumAge?: number;
  /** @default 'high' */
  desiredAccuracy?: DesiredAccuracy;
  /** @default true */
  persist?: boolean;
  extras?: Record<string, unknown>;
}

export interface WatchPositionOptions {
  /**
   * ms
   * @default 1000
   */
  interval?: number;
  /** @default 'high' */
  desiredAccuracy?: DesiredAccuracy;
  /** @default false */
  persist?: boolean;
  extras?: Record<string, unknown>;
}

export type WatchPositionCallback = (location: Location | null, error?: { code: ErrorCode; message: string }) => void;

export interface HeartbeatStatus {
  enabled: boolean;
  minInterval: number;
  maxInterval: number;
  lastRecordAt: string | null;
  lastHeartbeatAt: string | null;
  nextHeartbeatAt: string | null;
  strategy: HeartbeatStrategy;
  canScheduleExactAlarms: boolean;
  isIgnoringBatteryOptimizations: boolean;
  isDeviceIdleMode: boolean;
  isPowerSaveMode: boolean;
  pendingHeartbeats: number;
}

export interface BatteryOptimizationStatus {
  isIgnoringBatteryOptimizations: boolean;
  canScheduleExactAlarms: boolean;
  isDeviceIdleMode: boolean;
}

export interface PowerManagerInfo {
  manufacturer: string;
  available: boolean;
}

export interface DeviceInfo {
  platform: 'android' | 'web';
  manufacturer: string;
  model: string;
  brand: string;
  osVersion: string;
  sdkInt: number;
  pluginVersion: string;
  gmsAvailable: boolean;
  hmsAvailable: boolean;
  backend: LocationBackend;
  packagedProviders: string[];
  /** why a better location backend is not used (for example "gms: unavailable (status 2)"); null when none is better */
  backendFallbackReason: string | null;
  /** raw GoogleApiAvailability result (0 = success); null if GMS is not packaged or not checked yet */
  gmsStatus: number | null;
  /** raw HuaweiApiAvailability result (0 = success); null if HMS is not packaged or not checked yet */
  hmsStatus: number | null;
  /** true when HMS Core is older than 6.3.0.301; null if HMS is not packaged */
  hmsCoreOutdated: boolean | null;
  /** last LocationAvailability reported by GMS or HMS; only a hint (it can be wrong both ways); null if none */
  locationAvailableHint: boolean | null;
  /** number of CPU cores */
  cpuCores: number;
  /** CPU types (Build.SUPPORTED_ABIS), e.g. ["arm64-v8a"] */
  supportedAbis: string[];
}

export interface Sensors {
  accelerometer: boolean;
  gyroscope: boolean;
  magnetometer: boolean;
  significantMotion: boolean;
  stepCounter: boolean;
  stepDetector: boolean;
  barometer: boolean;
}

export interface PermissionStatus {
  location: PermissionState;
  backgroundLocation: PermissionState;
  activityRecognition: PermissionState;
  notifications: PermissionState;
}

export interface LogQuery {
  start?: number;
  end?: number;
  level?: LogLevel;
  limit?: number;
  order?: 'asc' | 'desc';
}

export interface LocationTrackingPlugin {
  /** Loads persisted config and state. Must resolve before most other methods (see NOT_READY rule). */
  ready(options?: ReadyOptions): Promise<State>;
  /** deep merge; arrays replaced; null resets key to default */
  setConfig(options: { config: Config }): Promise<State>;
  /** Resets the config to defaults, then applies the given config. */
  reset(options?: { config?: Config }): Promise<State>;
  getState(): Promise<State>;
  /** Starts location tracking (mode 'location'). */
  start(): Promise<State>;
  /** Starts geofence-only tracking (mode 'geofences'). */
  startGeofences(): Promise<State>;
  stop(): Promise<State>;
  /** Forces the motion state to moving or stationary. */
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
  /** uploads whole queue now; resolves with uploaded records */
  sync(): Promise<{ locations: Location[] }>;

  addGeofence(options: { geofence: Geofence }): Promise<void>;
  addGeofences(options: { geofences: Geofence[] }): Promise<void>;
  removeGeofence(options: { identifier: string }): Promise<void>;
  /** identifiers omitted (or null) = remove all; an empty array removes nothing */
  removeGeofences(options?: { identifiers?: string[] }): Promise<void>;
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
  uploadLog(options: {
    url: string;
    headers?: Record<string, string>;
    params?: Record<string, unknown>;
  }): Promise<{ success: boolean; status: number }>;
  emailLog(options: { email: string; subject?: string }): Promise<void>;

  addListener(eventName: 'location', listenerFunc: (e: Location) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'motionchange', listenerFunc: (e: MotionChangeEvent) => void): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'activitychange',
    listenerFunc: (e: ActivityChangeEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(eventName: 'providerchange', listenerFunc: (e: ProviderState) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'heartbeat', listenerFunc: (e: HeartbeatEvent) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'geofence', listenerFunc: (e: GeofenceEvent) => void): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'geofenceschange',
    listenerFunc: (e: GeofencesChangeEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(eventName: 'http', listenerFunc: (e: HttpEvent) => void): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'connectivitychange',
    listenerFunc: (e: ConnectivityChangeEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'powersavechange',
    listenerFunc: (e: PowerSaveChangeEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(eventName: 'enabledchange', listenerFunc: (e: EnabledChangeEvent) => void): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'notificationaction',
    listenerFunc: (e: NotificationActionEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(eventName: 'authorization', listenerFunc: (e: AuthorizationEvent) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'locationerror', listenerFunc: (e: LocationErrorEvent) => void): Promise<PluginListenerHandle>;
  addListener(eventName: 'storagefull', listenerFunc: (e: StorageFullEvent) => void): Promise<PluginListenerHandle>;
  removeAllListeners(): Promise<void>;
}
