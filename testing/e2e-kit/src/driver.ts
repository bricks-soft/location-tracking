import type { LatLon } from './types.ts';
import type { CapturedEvent } from './webview.ts';

export type Platform = 'android' | 'ios';

export const PLATFORMS: readonly Platform[] = ['android', 'ios'];

export type LocationGrant = 'always' | 'whenInUse' | 'none';

export interface ResetOptions {
  location: LocationGrant;
  position?: LatLon;
}

export interface RouteHandle {
  stop(): Promise<void>;
}

export interface PlatformDriver {
  readonly platform: Platform;
  readonly appId: string;
  readonly hostAddress: string;
  reset(options: ResetOptions): Promise<void>;
  launch(): Promise<number>;
  kill(): Promise<void>;
  pid(): Promise<number | null>;
  call<T = any>(method: string, options?: Record<string, unknown>, timeoutMs?: number): Promise<T>;
  captureEvents(names: readonly string[]): Promise<void>;
  drainEvents(): Promise<CapturedEvent[]>;
  setPosition(point: LatLon): Promise<void>;
  drive(points: readonly LatLon[], speedMps: number, signal?: AbortSignal): Promise<void>;
  startRoute(points: readonly LatLon[], speedMps: number): Promise<RouteHandle>;
  background(): Promise<void>;
  foreground(): Promise<void>;
  restartDevice(): Promise<void>;
  readAppFile(path: string): Promise<string | null>;
  dispose(): Promise<void>;
}
