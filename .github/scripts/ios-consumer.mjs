import { execFileSync } from 'node:child_process';
import { cpSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

const root = process.cwd();
const manager = process.argv[2];
const consumer = join(process.env.RUNNER_TEMP, 'ios-consumer');
const run = (command, args, cwd = consumer) => execFileSync(command, args, { cwd, stdio: 'inherit' });
const packed = JSON.parse(execFileSync('npm', ['pack', '--json'], { cwd: root, encoding: 'utf8' }));
mkdirSync(consumer, { recursive: true });
writeFileSync(join(consumer, 'package.json'), JSON.stringify({ name: 'ios-tarball-consumer', version: '1.0.0', private: true, dependencies: { '@bricks-soft/capacitor-location-tracking': `file:${resolve(packed[0].filename)}`, '@capacitor/core': '8.5.2', '@capacitor/ios': '8.5.2', '@capacitor/cli': '8.5.2' } }));
cpSync(join(root, 'example/www'), join(consumer, 'www'), { recursive: true });
writeFileSync(join(consumer, 'capacitor.config.json'), JSON.stringify({ appId: 'com.brickssoft.locationtracking.example', appName: 'LT Example', webDir: 'www', ios: { ...(manager === 'CocoaPods' ? { preferredContentMode: 'mobile' } : {}) } }));
run('npm', ['install']);
mkdirSync(join(consumer, 'www/vendor'), { recursive: true });
cpSync(join(consumer, 'node_modules/@capacitor/core/dist/capacitor.js'), join(consumer, 'www/vendor/capacitor.js'));
cpSync(join(consumer, 'node_modules/@bricks-soft/capacitor-location-tracking/dist/plugin.js'), join(consumer, 'www/vendor/plugin.js'));
run('npx', ['cap', 'add', 'ios', ...(manager === 'CocoaPods' ? ['--packagemanager', 'CocoaPods'] : [])]);
for (const file of ['AppDelegate.swift', 'SceneDelegate.swift', 'Info.plist']) {
  cpSync(join(root, 'example/ios/App/App', file), join(consumer, 'ios/App/App', file));
}
cpSync(join(root, 'example/ios/App/App/Base.lproj/LaunchScreen.storyboard'), join(consumer, 'ios/App/App/Base.lproj/LaunchScreen.storyboard'));
run('npx', ['cap', 'sync', 'ios']);
