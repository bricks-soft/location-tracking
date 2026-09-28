// Device profile detection of the e2e kit (Unit 7).
import type { Adb } from './adb.ts';

/** What the scenario wrapper needs to know about the device to honor `requires`. */
export interface DeviceProfile {
  /** ro.build.version.sdk */
  api: number;
  /** adbd runs as root (after `adb root` on userdebug images) */
  root: boolean;
  /** Google Play services installed and enabled (`pm list packages -e com.google.android.gms`) */
  gms: boolean;
  /** ro.kernel.qemu / ro.boot.qemu */
  emulator: boolean;
  /** ro.product.model */
  model: string;
  /** ro.product.cpu.abi */
  abi: string;
}

/** Parses `adb shell getprop` output (`[name]: [value]` lines) into a map. */
export function parseGetprop(text: string): Map<string, string> {
  const props = new Map<string, string>();
  for (const line of text.split('\n')) {
    const match = /^\[([^\]]+)\]:\s*\[(.*)\]\s*$/.exec(line.trim());
    if (match) props.set(match[1]!, match[2]!);
  }
  return props;
}

const cache = new Map<string, Promise<DeviceProfile>>();

/**
 * Detects the profile once per serial (cached for the process): waits for the device to finish booting, tries
 * `adb root`, then reads all properties with one `getprop` and checks Google Play services.
 */
export async function detectDevice(adb: Adb): Promise<DeviceProfile> {
  const key = `${adb.adbPath}|${adb.serial ?? ''}`;
  let pending = cache.get(key);
  if (!pending) {
    pending = detect(adb);
    cache.set(key, pending);
    pending.catch(() => cache.delete(key));
  }
  return pending;
}

async function detect(adb: Adb): Promise<DeviceProfile> {
  await adb.waitForBoot();
  const root = await adb.root();
  const props = parseGetprop(await adb.shell('getprop'));
  const api = Number.parseInt(props.get('ro.build.version.sdk') ?? '', 10);
  if (!Number.isFinite(api)) throw new Error('detectDevice: could not read ro.build.version.sdk');
  const gmsList = await adb.exec(['shell', 'pm list packages -e com.google.android.gms'], { allowFailure: true });
  const gms = gmsList.stdout.split('\n').some((line) => line.trim() === 'package:com.google.android.gms');
  return {
    api,
    root,
    gms,
    emulator: props.get('ro.kernel.qemu') === '1' || props.get('ro.boot.qemu') === '1',
    model: props.get('ro.product.model') ?? '',
    abi: props.get('ro.product.cpu.abi') ?? '',
  };
}
