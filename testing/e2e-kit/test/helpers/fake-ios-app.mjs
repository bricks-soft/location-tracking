import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const dir = join(process.argv[2], 'Library/Application Support/E2E');
mkdirSync(dir, { recursive: true });
const write = (name, value) => {
  writeFileSync(join(dir, `${name}.tmp`), JSON.stringify(value));
  renameSync(join(dir, `${name}.tmp`), join(dir, name));
};
let enabled = false;
let last = '';
const events = [];
write('ready.json', { ready: true, pid: process.pid });
setInterval(() => {
  let command;
  try {
    command = JSON.parse(readFileSync(join(dir, 'command.json'), 'utf8'));
  } catch {
    return;
  }
  if (command.id === last) return;
  last = command.id;
  const answer = (value) => write('response.json', { id: command.id, success: true, value });
  if (command.method === 'start') {
    enabled = true;
    events.push({ name: 'enabledchange', payload: { enabled }, receivedAt: Date.now() });
    answer({ enabled });
  } else if (command.method === 'getState') answer({ enabled, trackingMode: 'location' });
  else if (command.method === 'drainEvents') answer(events.splice(0));
  else if (command.method === 'captureEvents') answer(true);
  else write('response.json', { id: command.id, success: false, code: 'UNAVAILABLE', error: `${command.method} is not available` });
}, 50);
