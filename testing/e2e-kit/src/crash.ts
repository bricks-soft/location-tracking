// STUB — owned by Unit 7 (e2e-kit).
import type { Adb } from './adb.ts';
import { notImplemented } from './util.ts';

export interface CrashReport {
  kind: 'crash' | 'anr' | 'native';
  /** process name */
  process: string;
  pid?: number;
  /** exception class (crash) or ANR reason */
  exception: string;
  /** the full logcat excerpt */
  text: string;
}

/** Scans logcat (crash, main and system buffers) for crashes and ANRs of one app since [mark]. */
export class CrashScanner {
  readonly adb: Adb;
  readonly appId: string;

  constructor(adb: Adb, appId: string) {
    this.adb = adb;
    this.appId = appId;
  }

  /** Remembers the device time; later scans only look at lines after it. The scenario wrapper marks at start. */
  async mark(): Promise<void> {
    return notImplemented('CrashScanner.mark');
  }

  /** `FATAL EXCEPTION` / `ANR in` / native crash entries of [appId] since the mark. */
  async crashes(): Promise<CrashReport[]> {
    return notImplemented('CrashScanner.crashes');
  }

  /** Fails with every crash report since the mark. */
  async assertNoCrash(): Promise<void> {
    return notImplemented('CrashScanner.assertNoCrash');
  }

  /**
   * Fails if logcat shows `ForegroundServiceDidNotStartInTimeException` (or the pre-12 text "Context.startForegroundService()
   * did not then call Service.startForeground()") for [appId] since the mark, crash or not.
   */
  async assertNoFgsDidNotStartInTime(): Promise<void> {
    return notImplemented('CrashScanner.assertNoFgsDidNotStartInTime');
  }
}
