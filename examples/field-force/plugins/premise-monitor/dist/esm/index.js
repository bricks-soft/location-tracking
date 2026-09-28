// Hand-written ESM entry (for bundlers); the field-force app itself uses dist/plugin.js.
// Android only: on the web every method rejects with UNIMPLEMENTED (no web implementation is registered).
import { registerPlugin } from '@capacitor/core';

/**
 * startMonitoring({ premise: {id, name?, latitude, longitude, radius}, auditUrl? }) -> PremiseStatus
 * stopMonitoring() -> PremiseStatus
 * getStatus() -> PremiseStatus
 * getAuditLog({ limit? }) -> { entries: PremiseAuditEntry[] }
 * Types: index.d.ts; behavior: docs/e2e/architecture.md §7 and §10.
 */
export const PremiseMonitor = registerPlugin('PremiseMonitor');
