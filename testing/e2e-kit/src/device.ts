// STUB — owned by Unit 7 (e2e-kit).
import type { Adb } from './adb.ts';
import { notImplemented } from './util.ts';

/** What the scenario wrapper needs to know about the device to honor `requires`. */
export interface DeviceProfile {
  /** ro.build.version.sdk */
  api: number;
  /** adbd runs as root (after `adb root` on userdebug images) */
  root: boolean;
  /** Google Play services installed and enabled (`pm path com.google.android.gms`) */
  gms: boolean;
  /** ro.kernel.qemu / ro.boot.qemu */
  emulator: boolean;
  /** ro.product.model */
  model: string;
  /** ro.product.cpu.abi */
  abi: string;
}

/** Detects the profile once (tries `adb root` first) and caches it per serial. */
export async function detectDevice(adb: Adb): Promise<DeviceProfile> {
  return notImplemented('detectDevice');
}
