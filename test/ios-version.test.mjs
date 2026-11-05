import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

test('the iOS plugin reports the package.json version', () => {
  const { version } = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'));
  const swift = readFileSync(new URL('../ios/Sources/LocationTrackingPlugin/Core/PluginInfo.swift', import.meta.url), 'utf8');
  assert.equal(/static let version = "([^"]*)"/.exec(swift)?.[1], version);
});
