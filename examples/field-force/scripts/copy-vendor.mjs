// Copies the browser bundles into www/vendor/ (the app has no bundler) and writes www/env.js.
//   FF_BACKEND_URL  back office base URL as the device sees it (default http://10.0.2.2:8787, the host from an AVD)
import { copyFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const www = join(root, 'www');
const vendor = join(www, 'vendor');
mkdirSync(vendor, { recursive: true });

const files = [
  ['node_modules/@capacitor/core/dist/capacitor.js', 'capacitor.js'],
  ['node_modules/@bricks-soft/capacitor-location-tracking/dist/plugin.js', 'plugin.js'],
  ['node_modules/@bricks-soft/capacitor-premise-monitor/dist/plugin.js', 'premise-monitor.js'],
];
for (const [from, to] of files) {
  copyFileSync(join(root, from), join(vendor, to));
  console.log(`copied ${from} -> www/vendor/${to}`);
}

const env = { backendUrl: process.env.FF_BACKEND_URL || 'http://10.0.2.2:8787' };
writeFileSync(join(www, 'env.js'), `window.FF_ENV = ${JSON.stringify(env)};\n`);
console.log(`wrote www/env.js (backendUrl ${env.backendUrl})`);
