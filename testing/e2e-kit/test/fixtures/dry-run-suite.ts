// Fixture for test/scenario.test.ts: two scenarios whose bodies must never run in a dry run.
import { scenario } from '../../src/index.ts';

scenario('P-L01', 'rapid start/stop loop', async () => {
  throw new Error('the body of P-L01 ran during a dry run');
});

scenario(
  'P-H04',
  'deep Doze spacing',
  async () => {
    throw new Error('the body of P-H04 ran during a dry run');
  },
  { timeoutMs: 45 * 60_000, requires: { root: true, api: { min: 29 }, long: true } },
);
