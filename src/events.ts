// Owned by Unit 1 (TS wrapper + web).
import type { PluginListenerHandle } from '@capacitor/core';

import type {
  ActivityChangeEvent,
  AuthorizationEvent,
  ConnectivityChangeEvent,
  EnabledChangeEvent,
  GeofenceEvent,
  GeofencesChangeEvent,
  HeartbeatEvent,
  HttpEvent,
  Location,
  LocationTrackingEventMap,
  LocationTrackingEventName,
  MotionChangeEvent,
  NotificationActionEvent,
  PowerSaveChangeEvent,
  ProviderState,
} from './definitions';
import { LocationTracking } from './plugin';

/** Names of every event emitted by the plugin. */
export const Events = {
  LOCATION: 'location',
  MOTIONCHANGE: 'motionchange',
  ACTIVITYCHANGE: 'activitychange',
  PROVIDERCHANGE: 'providerchange',
  HEARTBEAT: 'heartbeat',
  GEOFENCE: 'geofence',
  GEOFENCESCHANGE: 'geofenceschange',
  HTTP: 'http',
  CONNECTIVITYCHANGE: 'connectivitychange',
  POWERSAVECHANGE: 'powersavechange',
  ENABLEDCHANGE: 'enabledchange',
  NOTIFICATIONACTION: 'notificationaction',
  AUTHORIZATION: 'authorization',
} as const;

interface UntypedListenerTarget {
  addListener(eventName: string, listenerFunc: (event: any) => void): Promise<PluginListenerHandle>;
}

/** Subscribes to any plugin event with a typed payload. */
export function on<K extends LocationTrackingEventName>(
  name: K,
  callback: (event: LocationTrackingEventMap[K]) => void,
): Promise<PluginListenerHandle> {
  return (LocationTracking as unknown as UntypedListenerTarget).addListener(name, callback);
}

export const onLocation = (cb: (e: Location) => void): Promise<PluginListenerHandle> => on('location', cb);
export const onMotionChange = (cb: (e: MotionChangeEvent) => void): Promise<PluginListenerHandle> =>
  on('motionchange', cb);
export const onActivityChange = (cb: (e: ActivityChangeEvent) => void): Promise<PluginListenerHandle> =>
  on('activitychange', cb);
export const onProviderChange = (cb: (e: ProviderState) => void): Promise<PluginListenerHandle> =>
  on('providerchange', cb);
export const onHeartbeat = (cb: (e: HeartbeatEvent) => void): Promise<PluginListenerHandle> => on('heartbeat', cb);
export const onGeofence = (cb: (e: GeofenceEvent) => void): Promise<PluginListenerHandle> => on('geofence', cb);
export const onGeofencesChange = (cb: (e: GeofencesChangeEvent) => void): Promise<PluginListenerHandle> =>
  on('geofenceschange', cb);
export const onHttp = (cb: (e: HttpEvent) => void): Promise<PluginListenerHandle> => on('http', cb);
export const onConnectivityChange = (cb: (e: ConnectivityChangeEvent) => void): Promise<PluginListenerHandle> =>
  on('connectivitychange', cb);
export const onPowerSaveChange = (cb: (e: PowerSaveChangeEvent) => void): Promise<PluginListenerHandle> =>
  on('powersavechange', cb);
export const onEnabledChange = (cb: (e: EnabledChangeEvent) => void): Promise<PluginListenerHandle> =>
  on('enabledchange', cb);
export const onNotificationAction = (cb: (e: NotificationActionEvent) => void): Promise<PluginListenerHandle> =>
  on('notificationaction', cb);
export const onAuthorization = (cb: (e: AuthorizationEvent) => void): Promise<PluginListenerHandle> =>
  on('authorization', cb);
