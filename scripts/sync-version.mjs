// Run by `npm version` (the "version" script) after it bumps package.json and before it commits and tags, so the
// release commit carries the new version in src/web/device.ts too. android/build.gradle reads package.json itself.
import { readFileSync, writeFileSync } from 'node:fs';

const { version } = JSON.parse(readFileSync('package.json', 'utf8'));
const file = 'src/web/device.ts';
writeFileSync(file, readFileSync(file, 'utf8').replace(/PLUGIN_VERSION = '[^']*'/, `PLUGIN_VERSION = '${version}'`));
