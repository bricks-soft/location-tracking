import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { test } from 'node:test';

const require = createRequire(import.meta.url);
const { LocationTracking } = require('../dist/plugin.cjs.js');

test('temporary full accuracy rejects UNIMPLEMENTED on web before and after ready', async () => {
  await assert.rejects(LocationTracking.requestTemporaryFullAccuracy({ purposeKey: 'Tracking' }), { code: 'UNIMPLEMENTED' });
  await LocationTracking.ready();
  await assert.rejects(LocationTracking.requestTemporaryFullAccuracy({ purposeKey: 'Tracking' }), { code: 'UNIMPLEMENTED' });
});
