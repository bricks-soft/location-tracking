// Copies the browser bundles into www/vendor/ (the example has no bundler).
import { copyFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const vendor = join(root, 'www', 'vendor');
mkdirSync(vendor, { recursive: true });

const files = [
  ['node_modules/@capacitor/core/dist/capacitor.js', 'capacitor.js'],
  ['node_modules/@bricks-soft/capacitor-location-tracking/dist/plugin.js', 'plugin.js'],
];
for (const [from, to] of files) {
  copyFileSync(join(root, from), join(vendor, to));
  console.log(`copied ${from} -> www/vendor/${to}`);
}
