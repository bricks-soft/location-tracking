// Hand-written types of the fake PremiseMonitor plugin (docs/e2e/architecture.md §10).
export interface Premise {
  id: string;
  name?: string;
  latitude: number;
  longitude: number;
  /** meters */
  radius: number;
}

export interface PremiseStatus {
  monitoring: boolean;
  premise: Premise | null;
  inside: boolean | null;
  serviceRunning: boolean;
  auditUrl: string | null;
  lastEntryAt: string | null;
  pendingUploads: number;
}

export interface PremiseAuditEntry {
  id: string;
  kind: 'record' | 'event' | 'premise';
  at: string;
  pid: number;
  js: boolean;
  source: 'manifest' | 'subscription';
  record?: Record<string, unknown>;
  name?: string;
  payload?: Record<string, unknown>;
  type?: string;
  premise_id?: string;
  distance_m?: number;
  location?: Record<string, unknown> | null;
  detail?: string;
}

export interface PremiseMonitorPlugin {
  startMonitoring(options: { premise: Premise; auditUrl?: string }): Promise<PremiseStatus>;
  stopMonitoring(): Promise<PremiseStatus>;
  getStatus(): Promise<PremiseStatus>;
  getAuditLog(options?: { limit?: number }): Promise<{ entries: PremiseAuditEntry[] }>;
}

export declare const PremiseMonitor: PremiseMonitorPlugin;
