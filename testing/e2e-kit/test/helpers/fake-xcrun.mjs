#!/usr/bin/env node
import { spawn } from 'node:child_process';
import { appendFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const args = process.argv.slice(2);
const data = process.env.FAKE_SIM_DATA;
appendFileSync(join(data, 'calls.log'), `${args.join(' ')}\n`);
const [tool, command, ...rest] = args;
if (tool !== 'simctl') process.exit(2);
const state = join(data, 'app.pid');
switch (command) {
  case 'get_app_container':
    process.stdout.write(`${data}\n`);
    break;
  case 'launch': {
    const terminate = rest[0] === '--terminate-running-process';
    const [, bundle, ...appArgs] = terminate ? rest.slice(1) : rest;
    if (bundle === 'com.apple.Preferences') {
      process.stdout.write(`${bundle}: 1\n`);
      break;
    }
    if (terminate) {
      try {
        process.kill(Number(readFileSync(state, 'utf8')), 'SIGKILL');
      } catch {}
    }
    const child = spawn(process.execPath, [new URL('./fake-ios-app.mjs', import.meta.url).pathname, data, ...appArgs], { detached: true, stdio: 'ignore' });
    child.unref();
    writeFileSync(state, String(child.pid));
    process.stdout.write(`${bundle}: ${child.pid}\n`);
    break;
  }
  case 'list':
    process.stdout.write(JSON.stringify({ devices: { 'com.apple.CoreSimulator.SimRuntime.iOS-17-5': [{ udid: 'FAKE-UDID' }] } }));
    break;
  case 'location':
    if (rest[1] === 'set' && rest[2].startsWith('91.')) {
      process.stderr.write('Invalid coordinate\n');
      process.exit(3);
    }
    break;
  default:
    break;
}
mkdirSync(data, { recursive: true });
