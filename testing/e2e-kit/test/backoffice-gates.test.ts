import assert from 'node:assert/strict';
import { test } from 'node:test';
import { MockBackOffice } from '../src/backoffice.ts';

test('a held request is observable before its response is explicitly released', { timeout: 5000 }, async () => {
  const office = new MockBackOffice({ host: '127.0.0.1', port: 0 });
  await office.start();
  try {
    office.setFault({ path: '/locations', hold: 'upload', status: 401 });
    let answered = false;
    const pending = fetch(office.hostUrl('/locations'), { method: 'POST', body: '{}', signal: AbortSignal.timeout(4000) }).then((response) => { answered = true; return response; });
    await office.waitFor((o) => o.requests({ path: '/locations' }).length === 1, { timeoutMs: 2000, intervalMs: 10 });
    const gate = await fetch(office.hostUrl('/__gates/upload')).then((response) => response.json());
    assert.deepEqual(gate, { arrivals: 1, waiting: 1 });
    assert.equal(answered, false);
    await fetch(office.hostUrl('/__gates/upload'), { method: 'POST' });
    assert.equal((await pending).status, 401);
  } finally { await office.stop(); }
});

test('reset releases held responses and forgets gates', { timeout: 5000 }, async () => {
  const office = new MockBackOffice({ host: '127.0.0.1', port: 0 });
  await office.start();
  try {
    office.setFault({ path: '/locations', hold: 'upload', status: 413 });
    const pending = fetch(office.hostUrl('/locations'), { method: 'POST', body: '{}', signal: AbortSignal.timeout(4000) });
    await office.waitFor((o) => o.requests({ path: '/locations' }).length === 1, { timeoutMs: 2000, intervalMs: 10 });
    office.reset();
    assert.equal((await pending).status, 413);
    assert.deepEqual(await fetch(office.hostUrl('/__gates/upload')).then((response) => response.json()), { arrivals: 0, waiting: 0 });
  } finally { await office.stop(); }
});
