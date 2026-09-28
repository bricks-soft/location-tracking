// CONTRACT (scaffold, round 2): shared data shapes of the e2e kit. See docs/e2e/architecture.md §7-§8.
// These mirror the plugin's wire format (docs/wire-format.md) without importing the plugin package, so the kit and the
// field-force suite can move to another repository (the Bricks app) unchanged.

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

export type LocationBackend = 'gms' | 'hms' | 'android' | 'web';

export interface WireCoords {
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

export interface WireProviderState {
  enabled: boolean;
  gps: boolean;
  network: boolean;
  permission: 'always' | 'when_in_use' | 'denied';
  accuracy: 'precise' | 'approximate' | 'none';
  backend: LocationBackend;
}

/** Optional `heartbeat` object of a heartbeat record (round 2). */
export interface WireHeartbeatMeta {
  strategy: 'exact' | 'listener_with_backup' | 'idle_paced';
  min_interval: number;
  max_interval: number;
  next_at: string | null;
  battery_exempt: boolean;
  device_idle: boolean;
}

/** One record as the plugin sends it (`sent_at` only in HTTP bodies) and as JS/native listeners receive it. */
export interface WireRecord {
  uuid: string;
  event: RecordEvent;
  timestamp: string | null;
  recorded_at: string;
  sent_at?: string;
  elapsed_realtime_ms: number;
  boot_count: number;
  is_moving: boolean;
  odometer: number;
  mock: boolean;
  coords: WireCoords | null;
  activity: { type: string; confidence: number };
  battery: { level: number; is_charging: boolean };
  backend: LocationBackend | null;
  extras?: Record<string, unknown>;
  geofence?: { identifier: string; action: 'ENTER' | 'EXIT' | 'DWELL'; extras?: Record<string, unknown> };
  provider?: WireProviderState;
  reason?: string;
  heartbeat?: WireHeartbeatMeta;
}

/** The JS/native `State` shape (config is the full plugin config JSON). */
export interface StateJson {
  enabled: boolean;
  trackingMode: 'location' | 'geofences';
  isMoving: boolean;
  odometer: number;
  backend: LocationBackend;
  lastRecordAt: string | null;
  config: Record<string, any>;
}

/** The JS/native `HeartbeatStatus` shape. */
export interface HeartbeatStatusJson {
  enabled: boolean;
  minInterval: number;
  maxInterval: number;
  lastRecordAt: string | null;
  lastHeartbeatAt: string | null;
  nextHeartbeatAt: string | null;
  strategy: 'exact' | 'listener_with_backup' | 'idle_paced' | 'disabled';
  canScheduleExactAlarms: boolean;
  isIgnoringBatteryOptimizations: boolean;
  isDeviceIdleMode: boolean;
  isPowerSaveMode: boolean;
  pendingHeartbeats: number;
}

/** JS `Geofence` shape (circles only in the e2e suites). */
export interface GeofenceJson {
  identifier: string;
  latitude?: number;
  longitude?: number;
  radius?: number;
  vertices?: [number, number][];
  notifyOnEntry?: boolean;
  notifyOnExit?: boolean;
  notifyOnDwell?: boolean;
  loiteringDelay?: number;
  extras?: Record<string, unknown>;
}

/** A point of a replayed route or a geo fix. */
export interface LatLon {
  lat: number;
  lon: number;
  /** meters above sea level */
  alt?: number;
}

// ---- PremiseMonitor (fake companion plugin of the field-force app), docs/e2e/architecture.md §7

/** A circular premise. */
export interface Premise {
  id: string;
  name?: string;
  latitude: number;
  longitude: number;
  /** meters */
  radius: number;
}

export type PremiseEntryType =
  | 'monitoring_started'
  | 'monitoring_stopped'
  | 'enter'
  | 'exit'
  | 'presence_violation'
  | 'service_started'
  | 'service_stopped'
  | 'service_start_failed';

/** One PremiseMonitor audit entry, as POSTed to `/premise-audit` and returned by `getAuditLog()`. */
export interface PremiseAuditEntry {
  /** uuid of the entry */
  id: string;
  /** what PremiseMonitor received or decided */
  kind: 'record' | 'event' | 'premise';
  /** when PremiseMonitor created the entry (ISO-8601 UTC ms) */
  at: string;
  /** Android pid of the process that created the entry (tells process restarts apart) */
  pid: number;
  /** true if a PremiseMonitor JS plugin instance was loaded in this process when the entry was created */
  js: boolean;
  /** how the listener was registered: manifest meta-data or LocationTrackingNative.addListener */
  source: 'manifest' | 'subscription';
  /** kind 'record': the wire record from LocationTrackingListener.onRecord */
  record?: WireRecord;
  /** kind 'event': the JS event name and payload from LocationTrackingListener.onEvent */
  name?: string;
  payload?: Record<string, unknown>;
  /** kind 'premise': PremiseMonitor's own audit */
  type?: PremiseEntryType;
  premise_id?: string;
  /** kind 'premise': distance of the triggering fix from the premise centre, meters */
  distance_m?: number;
  /** kind 'premise': the triggering record (enter/exit/presence_violation), if any */
  location?: WireRecord | null;
  detail?: string;
}

/** Body of `POST /premise-audit`. */
export interface PremiseAuditBody {
  device_id?: string;
  entries: PremiseAuditEntry[];
}

/** `PremiseMonitor.getStatus()` / e2e command `premise.status`. */
export interface PremiseStatus {
  monitoring: boolean;
  premise: Premise | null;
  /** null = unknown yet */
  inside: boolean | null;
  serviceRunning: boolean;
  auditUrl: string | null;
  lastEntryAt: string | null;
  /** entries not yet accepted by the audit endpoint */
  pendingUploads: number;
}
