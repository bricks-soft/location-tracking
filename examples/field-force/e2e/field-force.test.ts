// PLACEHOLDER — owned by Unit 14 (Field-force suite). Scenario ids and titles: docs/e2e/architecture.md §9.
import { scenario } from '@bricks-soft/e2e-kit';

scenario('F-01', 'app launch auto-starts tracking: tracking_start + motionchange, device details in params, battery', async () => {
  throw new Error('not implemented');
});

scenario(
  'F-02',
  '02:00 stop: stop time computed from the device clock, tracking_stop reason stop_after_elapsed',
  async () => {
    throw new Error('not implemented');
  },
  { requires: { root: true } },
);

scenario(
  'F-03',
  '02:00 stop still happens after a process kill (restore) and after a reboot',
  async () => {
    throw new Error('not implemented');
  },
  { timeoutMs: 20 * 60_000, requires: { root: true } },
);

scenario(
  'F-04',
  'route replay: uploads batched within syncInterval, odometer about route length, travel time',
  async () => {
    throw new Error('not implemented');
  },
  { timeoutMs: 15 * 60_000 },
);

scenario('F-05', 'stationary: no GPS, heartbeats on cadence with an older location.timestamp', async () => {
  throw new Error('not implemented');
});

scenario('F-06', 'online/offline audit on the server: heartbeat cadence, stop records tracking_stop', async () => {
  throw new Error('not implemented');
});

scenario('F-07', 'premise ENTER: PremiseMonitor service runs, its audit endpoint receives enter and every later record/event', async () => {
  throw new Error('not implemented');
});

scenario('F-08', 'app backgrounded and activity destroyed: PremiseMonitor still receives events natively', async () => {
  throw new Error('not implemented');
});

scenario(
  'F-09',
  'kill -9 inside the premise: process restored, listener receives events before any JS',
  async () => {
    throw new Error('not implemented');
  },
  { requires: { root: true } },
);

scenario(
  'F-10',
  'reboot inside the premise: listener receives tracking_start boot + heartbeats, PremiseMonitor service restored',
  async () => {
    throw new Error('not implemented');
  },
  { timeoutMs: 15 * 60_000 },
);

scenario('F-11', 'premise EXIT: exit audit, PremiseMonitor service stops', async () => {
  throw new Error('not implemented');
});

scenario('F-12', 'presence validation: a fix outside the radius while inside is flagged by PremiseMonitor', async () => {
  throw new Error('not implemented');
});
