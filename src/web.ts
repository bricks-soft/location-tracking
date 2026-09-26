// STUB — owned by Unit 1 (TS wrapper + web). Replace this implementation.
import { WebPlugin } from '@capacitor/core';

import type {
  BatteryOptimizationStatus,
  CallbackID,
  Config,
  CurrentPositionOptions,
  DeviceInfo,
  Geofence,
  HeartbeatStatus,
  InsertLocationInput,
  Location,
  LocationTrackingPlugin,
  LogLevel,
  LogQuery,
  PermissionStatus,
  PermissionType,
  PowerManagerInfo,
  ProviderState,
  ReadyOptions,
  Sensors,
  State,
  WatchPositionCallback,
  WatchPositionOptions,
} from './definitions';

export class LocationTrackingWeb extends WebPlugin implements LocationTrackingPlugin {
  async ready(_options?: ReadyOptions): Promise<State> {
    throw this.unimplemented('Not implemented on web.');
  }
  async setConfig(_options: { config: Config }): Promise<State> {
    throw this.unimplemented('Not implemented on web.');
  }
  async reset(_options?: { config?: Config }): Promise<State> {
    throw this.unimplemented('Not implemented on web.');
  }
  async getState(): Promise<State> {
    throw this.unimplemented('Not implemented on web.');
  }
  async start(): Promise<State> {
    throw this.unimplemented('Not implemented on web.');
  }
  async startGeofences(): Promise<State> {
    throw this.unimplemented('Not implemented on web.');
  }
  async stop(): Promise<State> {
    throw this.unimplemented('Not implemented on web.');
  }
  async changePace(_options: { isMoving: boolean }): Promise<void> {
    throw this.unimplemented('Not implemented on web.');
  }

  async getCurrentPosition(_options?: CurrentPositionOptions): Promise<Location> {
    throw this.unimplemented('Not implemented on web.');
  }
  async watchPosition(_options: WatchPositionOptions, _callback: WatchPositionCallback): Promise<CallbackID> {
    throw this.unimplemented('Not implemented on web.');
  }
  async clearWatch(_options: { id: CallbackID }): Promise<void> {
    throw this.unimplemented('Not implemented on web.');
  }

  async getOdometer(): Promise<{ odometer: number }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async setOdometer(_options: { odometer: number }): Promise<{ odometer: number }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async resetOdometer(): Promise<{ odometer: number }> {
    throw this.unimplemented('Not implemented on web.');
  }

  async getLocations(_options?: { limit?: number }): Promise<{ locations: Location[] }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async getCount(): Promise<{ count: number }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async insertLocation(_options: { location: InsertLocationInput }): Promise<{ uuid: string }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async destroyLocations(): Promise<{ count: number }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async destroyLocation(_options: { uuid: string }): Promise<{ deleted: boolean }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async sync(): Promise<{ locations: Location[] }> {
    throw this.unimplemented('Not implemented on web.');
  }

  async addGeofence(_options: { geofence: Geofence }): Promise<void> {
    throw this.unimplemented('Not implemented on web.');
  }
  async addGeofences(_options: { geofences: Geofence[] }): Promise<void> {
    throw this.unimplemented('Not implemented on web.');
  }
  async removeGeofence(_options: { identifier: string }): Promise<void> {
    throw this.unimplemented('Not implemented on web.');
  }
  async removeGeofences(_options?: { identifiers?: string[] }): Promise<void> {
    throw this.unimplemented('Not implemented on web.');
  }
  async getGeofences(): Promise<{ geofences: Geofence[] }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async getGeofence(_options: { identifier: string }): Promise<{ geofence: Geofence | null }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async geofenceExists(_options: { identifier: string }): Promise<{ exists: boolean }> {
    throw this.unimplemented('Not implemented on web.');
  }

  async getHeartbeatStatus(): Promise<HeartbeatStatus> {
    throw this.unimplemented('Not implemented on web.');
  }

  async getProviderState(): Promise<ProviderState> {
    throw this.unimplemented('Not implemented on web.');
  }
  async isPowerSaveMode(): Promise<{ isPowerSaveMode: boolean }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async getBatteryOptimizationStatus(): Promise<BatteryOptimizationStatus> {
    throw this.unimplemented('Not implemented on web.');
  }
  async openBatteryOptimizationSettings(): Promise<{ opened: boolean }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async getPowerManagerInfo(): Promise<PowerManagerInfo> {
    throw this.unimplemented('Not implemented on web.');
  }
  async openPowerManagerSettings(): Promise<{ opened: boolean }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async openLocationSettings(): Promise<{ opened: boolean }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async openAppSettings(): Promise<{ opened: boolean }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async getDeviceInfo(): Promise<DeviceInfo> {
    throw this.unimplemented('Not implemented on web.');
  }
  async getSensors(): Promise<Sensors> {
    throw this.unimplemented('Not implemented on web.');
  }

  async checkPermissions(): Promise<PermissionStatus> {
    throw this.unimplemented('Not implemented on web.');
  }
  async requestPermissions(_options?: { permissions?: PermissionType[] }): Promise<PermissionStatus> {
    throw this.unimplemented('Not implemented on web.');
  }

  async log(_options: { level: Exclude<LogLevel, 'off'>; message: string }): Promise<void> {
    throw this.unimplemented('Not implemented on web.');
  }
  async getLog(_options?: LogQuery): Promise<{ log: string }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async destroyLog(): Promise<void> {
    throw this.unimplemented('Not implemented on web.');
  }
  async uploadLog(_options: {
    url: string;
    headers?: Record<string, string>;
    params?: Record<string, unknown>;
  }): Promise<{ success: boolean; status: number }> {
    throw this.unimplemented('Not implemented on web.');
  }
  async emailLog(_options: { email: string; subject?: string }): Promise<void> {
    throw this.unimplemented('Not implemented on web.');
  }
}
