// Hand-written types of the fake PremiseMonitor plugin (docs/e2e/architecture.md §7 and §10).

/** A circular premise. */
export interface Premise {
  /** Non-empty (trimmed), at most 92 characters. The tracking plugin geofence is `premise:<id>`. */
  id: string;
  name?: string;
  latitude: number;
  longitude: number;
  /** meters, > 0 */
  radius: number;
}

/** `getStatus()`, and the result of `startMonitoring()` / `stopMonitoring()`. */
export interface PremiseStatus {
  monitoring: boolean;
  /** the monitored premise; null while not monitoring */
  premise: Premise | null;
  /** null = unknown (no ENTER/EXIT since monitoring started, or tracking stopped since) */
  inside: boolean | null;
  /** PremiseMonitor's own foreground service runs (started on ENTER, stopped on EXIT) */
  serviceRunning: boolean;
  /** kept after stopMonitoring(), so pending entries are still uploaded */
  auditUrl: string | null;
  /** `at` of the newest audit entry */
  lastEntryAt: string | null;
  /** entries not yet accepted (2xx) by the audit endpoint */
  pendingUploads: number;
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

/** One audit entry, as POSTed to the audit URL and returned by `getAuditLog()`. */
export interface PremiseAuditEntry {
  /** uuid of the entry */
  id: string;
  /** 'record' / 'event': received from the tracking plugin; 'premise': PremiseMonitor's own audit */
  kind: 'record' | 'event' | 'premise';
  /** when PremiseMonitor created the entry (ISO-8601 UTC ms) */
  at: string;
  /** Android pid of the process that created the entry (tells process restarts apart) */
  pid: number;
  /** true if a PremiseMonitor JS plugin instance was loaded in that process */
  js: boolean;
  /** how the listener was registered; this plugin uses the manifest declaration */
  source: 'manifest' | 'subscription';
  /** kind 'record': the tracking plugin's wire record */
  record?: Record<string, unknown>;
  /** kind 'event': the tracking plugin's JS event name and payload */
  name?: string;
  payload?: Record<string, unknown>;
  /** kind 'premise' */
  type?: PremiseEntryType;
  premise_id?: string;
  /** kind 'premise' (enter, exit, presence_violation): distance of the fix from the premise centre, meters */
  distance_m?: number;
  /** kind 'premise': the triggering record (enter, exit, presence_violation), else null */
  location?: Record<string, unknown> | null;
  /**
   * kind 'premise': service_started / service_stopped: the reason ('enter', 'restore', 'retry', 'exit',
   * 'stop_monitoring', 'premise_changed', 'tracking_stop: <reason>', 'destroyed'); service_start_failed: the
   * exception class and message; presence_violation: the arithmetic; monitoring_started: the premise and audit URL.
   */
  detail?: string;
}

/** Body of `POST <auditUrl>`. */
export interface PremiseAuditBody {
  device_id: string;
  entries: PremiseAuditEntry[];
}

export interface PremiseMonitorPlugin {
  /**
   * Starts monitoring `premise` (a tracking plugin geofence `premise:<id>`, ENTER + EXIT) and uploads the audit log to
   * `auditUrl` (http or https; omitted = keep entries local). Calling it again with the same premise keeps the state.
   * Rejects with `INVALID_ARGUMENT` for a bad premise or URL, or with the tracking plugin's error code when the
   * geofence cannot be added.
   */
  startMonitoring(options: { premise: Premise; auditUrl?: string }): Promise<PremiseStatus>;
  /** Stops monitoring: removes the geofence and stops the service. Records are still audited. */
  stopMonitoring(): Promise<PremiseStatus>;
  getStatus(): Promise<PremiseStatus>;
  /** The newest `limit` entries (default and `limit <= 0`: all kept entries), newest last. */
  getAuditLog(options?: { limit?: number }): Promise<{ entries: PremiseAuditEntry[] }>;
}

export declare const PremiseMonitor: PremiseMonitorPlugin;
