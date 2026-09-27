// Owned by Unit 1 (TS wrapper + web).
import { registerPlugin } from '@capacitor/core';

import type { LocationTrackingPlugin } from './definitions';

// Capacitor calls the web factory again for every call made before the first one resolves, so the (stateful) web
// implementation is memoized: concurrent first calls such as `onLocation(...)` + `ready(...)` share one instance.
let webImplementation: Promise<LocationTrackingPlugin> | undefined;

/** The Capacitor plugin object. */
export const LocationTracking = registerPlugin<LocationTrackingPlugin>('LocationTracking', {
  web: () => webImplementation || (webImplementation = import('./web').then((m) => new m.LocationTrackingWeb())),
});
