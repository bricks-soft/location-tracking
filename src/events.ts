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

/**
 * Subscribes to any plugin event with a typed payload. Call `remove()` on the resolved handle to unsubscribe.
 * The helpers below are standalone functions on purpose: Capacitor's plugin proxy turns unknown properties of
 * `LocationTracking` into native calls, so nothing is attached to it.
 */
export function on<K extends LocationTrackingEventName>(
  name: K,
  callback: (event: LocationTrackingEventMap[K]) => void,
): Promise<PluginListenerHandle> {
  return (LocationTracking as unknown as UntypedListenerTarget).addListener(name, callback);
}

/** Every recorded location, including persisted `current_position` / `watch_position` fixes and motion changes. */
export const onLocation = (cb: (e: Location) => void): Promise<PluginListenerHandle> => on('location', cb);
/** Moving / stationary transitions, with the fix at the moment of the change. */
export const onMotionChange = (cb: (e: MotionChangeEvent) => void): Promise<PluginListenerHandle> =>
  on('motionchange', cb);
/** Detected activity (still, walking, in_vehicle, ...). */
export const onActivityChange = (cb: (e: ActivityChangeEvent) => void): Promise<PluginListenerHandle> =>
  on('activitychange', cb);
/** Location services or permission changes. */
export const onProviderChange = (cb: (e: ProviderState) => void): Promise<PluginListenerHandle> =>
  on('providerchange', cb);
/** Heartbeat records, created when no record was made for `heartbeat.minInterval` seconds while tracking. */
export const onHeartbeat = (cb: (e: HeartbeatEvent) => void): Promise<PluginListenerHandle> => on('heartbeat', cb);
/** Geofence ENTER / EXIT / DWELL transitions. */
export const onGeofence = (cb: (e: GeofenceEvent) => void): Promise<PluginListenerHandle> => on('geofence', cb);
/** Geofences added to or removed from monitoring. */
export const onGeofencesChange = (cb: (e: GeofencesChangeEvent) => void): Promise<PluginListenerHandle> =>
  on('geofenceschange', cb);
/** Result of every HTTP upload request. */
export const onHttp = (cb: (e: HttpEvent) => void): Promise<PluginListenerHandle> => on('http', cb);
/** Network connectivity changes. */
export const onConnectivityChange = (cb: (e: ConnectivityChangeEvent) => void): Promise<PluginListenerHandle> =>
  on('connectivitychange', cb);
/** Battery-saver mode changes. */
export const onPowerSaveChange = (cb: (e: PowerSaveChangeEvent) => void): Promise<PluginListenerHandle> =>
  on('powersavechange', cb);
/** Tracking enabled / disabled. */
export const onEnabledChange = (cb: (e: EnabledChangeEvent) => void): Promise<PluginListenerHandle> =>
  on('enabledchange', cb);
/** Taps on the foreground notification's action buttons. */
export const onNotificationAction = (cb: (e: NotificationActionEvent) => void): Promise<PluginListenerHandle> =>
  on('notificationaction', cb);
/** JWT refresh results. */
export const onAuthorization = (cb: (e: AuthorizationEvent) => void): Promise<PluginListenerHandle> =>
  on('authorization', cb);
