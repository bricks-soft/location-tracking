// A scripted fake `adb` for the kit's node:test tests: an executable CommonJS script in a temp directory that records
// every invocation (arguments and stdin) and answers from JSON rules. Used through AdbOptions.adbPath or E2E_ADB.
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

/** One scripted answer. The first rule whose [match] matches the arguments (after `-s <serial>`, joined by spaces) answers. */
export interface FakeRule {
  /** JS regex source; `{{name}}` is replaced by a captured variable first; named groups `(?<name>...)` capture variables */
  match: string;
  stdout?: string;
  /** binary stdout (base64), e.g. a PNG */
  stdoutBase64?: string;
  stderr?: string;
  /** exit code (default 0) */
  code?: number;
  /** answer at most this many times, then fall through to later rules */
  times?: number;
  /** only when these variables were captured by an earlier call */
  requires?: string[];
  /** sleep before answering, ms */
  sleepMs?: number;
}

export interface FakeCall {
  /** arguments after `-s <serial>` */
  args: string[];
  /** the serial given with `-s`, if any */
  serial: string | undefined;
  input: string;
  at: number;
}

const SCRIPT = String.raw`
'use strict';
const fs = require('fs');
const path = require('path');
const DIR = process.env.FAKE_ADB_DIR_OVERRIDE || __DIR__;
let args = process.argv.slice(2);
let serial;
if (args[0] === '-s') { serial = args[1]; args = args.slice(2); }
let input = '';
try { input = fs.readFileSync(0, 'utf8'); } catch (e) { /* no stdin */ }
const statePath = path.join(DIR, 'state.json');
const lockPath = path.join(DIR, 'state.lock');
const sleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
// The kit runs adb calls concurrently (the broadcast and the logcat poll overlap), so the read-modify-write of
// state.json holds an exclusive lock file; otherwise a poll could overwrite the id the broadcast just captured.
const lock = () => {
  const started = Date.now();
  for (;;) {
    try { return fs.openSync(lockPath, 'wx'); } catch (e) {
      if (e.code !== 'EEXIST') throw e;
      // A lock older than 5 s belongs to a crashed call.
      try { if (Date.now() - fs.statSync(lockPath).mtimeMs > 5000) fs.rmSync(lockPath, { force: true }); } catch (e2) { /* gone */ }
      if (Date.now() - started > 20000) throw new Error('fake adb: state lock timeout');
      sleep(2);
    }
  }
};
const unlock = (fd) => { fs.closeSync(fd); fs.rmSync(lockPath, { force: true }); };
const readJson = (p, fallback) => { try { return JSON.parse(fs.readFileSync(p, 'utf8')); } catch (e) { return fallback; } };
fs.appendFileSync(path.join(DIR, 'calls.jsonl'), JSON.stringify({ args, serial, input, at: Date.now() }) + '\n');
const rules = readJson(path.join(DIR, 'rules.json'), []);
const joined = args.join(' ');
const lockFd = lock();
const state = readJson(statePath, { vars: {}, counts: {} });
const fill = (text) => String(text).replace(/\{\{(\w+)\}\}/g, (_, name) => (state.vars[name] !== undefined ? state.vars[name] : ''));
for (let i = 0; i < rules.length; i++) {
  const rule = rules[i];
  const count = state.counts[i] || 0;
  if (rule.times !== undefined && count >= rule.times) continue;
  if (rule.requires && !rule.requires.every((name) => state.vars[name] !== undefined)) continue;
  const m = new RegExp(fill(rule.match)).exec(joined);
  if (!m) continue;
  state.counts[i] = count + 1;
  if (m.groups) for (const [k, v] of Object.entries(m.groups)) if (v !== undefined) state.vars[k] = v;
  const tmp = statePath + '.' + process.pid;
  fs.writeFileSync(tmp, JSON.stringify(state));
  fs.renameSync(tmp, statePath);
  unlock(lockFd);
  if (rule.sleepMs) Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, rule.sleepMs);
  if (rule.stdoutBase64) process.stdout.write(Buffer.from(rule.stdoutBase64, 'base64'));
  else if (rule.stdout !== undefined) process.stdout.write(fill(rule.stdout));
  if (rule.stderr !== undefined) process.stderr.write(fill(rule.stderr));
  process.exitCode = rule.code || 0;
  return;
}
unlock(lockFd);
`;

/** A fake adb in its own temp directory. */
export class FakeAdb {
  readonly dir: string;
  /** executable path to use as AdbOptions.adbPath / E2E_ADB */
  readonly path: string;

  constructor(rules: FakeRule[] = []) {
    this.dir = mkdtempSync(join(tmpdir(), 'fake-adb-'));
    this.path = join(this.dir, 'adb.cjs');
    writeFileSync(this.path, `#!${process.execPath}\n${SCRIPT.replace('__DIR__', JSON.stringify(this.dir))}`);
    chmodSync(this.path, 0o755);
    this.setRules(rules);
  }

  /** Replaces the rules and forgets rule counters and captured variables. */
  setRules(rules: FakeRule[]): void {
    writeFileSync(join(this.dir, 'rules.json'), JSON.stringify(rules));
    writeFileSync(join(this.dir, 'state.json'), JSON.stringify({ vars: {}, counts: {} }));
  }

  /** Variables captured so far. */
  vars(): Record<string, string> {
    return (JSON.parse(readFileSync(join(this.dir, 'state.json'), 'utf8')) as { vars: Record<string, string> }).vars;
  }

  calls(): FakeCall[] {
    let text = '';
    try {
      text = readFileSync(join(this.dir, 'calls.jsonl'), 'utf8');
    } catch {
      return [];
    }
    return text
      .split('\n')
      .filter((line) => line.trim() !== '')
      .map((line) => JSON.parse(line) as FakeCall);
  }

  /** Every call as its arguments joined by spaces (`shell <command>` for shell calls). */
  commandLines(): string[] {
    return this.calls().map((call) => call.args.join(' '));
  }

  /** Forgets the recorded calls. */
  clearCalls(): void {
    writeFileSync(join(this.dir, 'calls.jsonl'), '');
  }

  cleanup(): void {
    rmSync(this.dir, { recursive: true, force: true });
  }
}

/** Escapes [text] for use inside a FakeRule.match regex. */
export function re(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
