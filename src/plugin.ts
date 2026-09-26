// Owned by Unit 1 (TS wrapper + web).
import { registerPlugin } from '@capacitor/core';

import type { LocationTrackingPlugin } from './definitions';

/** The Capacitor plugin object. */
export const LocationTracking = registerPlugin<LocationTrackingPlugin>('LocationTracking', {
  web: () => import('./web').then((m) => new m.LocationTrackingWeb()),
});
