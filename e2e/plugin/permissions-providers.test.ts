// PLACEHOLDER — owned by Unit 11 (Plugin suite C permissions/providers/geofences). Scenario ids and titles: docs/e2e/architecture.md §9.
import { scenario } from '@bricks-soft/e2e-kit';

scenario(
  'P-P01',
  'revoke fine location (keep coarse): process killed, relaunch, providerchange accuracy approximate',
  async () => {
    throw new Error('not implemented');
  },
  { requires: { api: 31 } },
);

scenario('P-P02', 'revoke all location: restore records tracking_stop reason permission_denied', async () => {
  throw new Error('not implemented');
});

scenario(
  'P-P03',
  'revoke background location: providerchange permission when_in_use, background behavior',
  async () => {
    throw new Error('not implemented');
  },
  { requires: { api: 29 } },
);

scenario(
  'P-P04',
  'revoke ACTIVITY_RECOGNITION: tracking continues with distance-based motion',
  async () => {
    throw new Error('not implemented');
  },
  { requires: { api: 29 } },
);

scenario(
  'P-P05',
  'revoke POST_NOTIFICATIONS while tracking: tracking continues',
  async () => {
    throw new Error('not implemented');
  },
  { requires: { api: 33 } },
);

scenario('P-P06', 'location services off/on: providerchange enabled=false/true, geofences re-registered', async () => {
  throw new Error('not implemented');
});

scenario(
  'P-P07',
  'locationProvider auto -> android -> gms at runtime: backend changes in records',
  async () => {
    throw new Error('not implemented');
  },
  { requires: { gms: true } },
);

scenario(
  'P-P08',
  'image without Google Play services: backend android, tracking works',
  async () => {
    throw new Error('not implemented');
  },
  { requires: { gms: false } },
);

scenario('P-P09', 'mock locations: mock:true; rejectMockLocations drops them', async () => {
  throw new Error('not implemented');
});

scenario('P-P10', 'circular geofence ENTER / EXIT / DWELL via geo fix', async () => {
  throw new Error('not implemented');
});

scenario(
  'P-P11',
  'geofences re-registered after reboot',
  async () => {
    throw new Error('not implemented');
  },
  { timeoutMs: 15 * 60_000 },
);
