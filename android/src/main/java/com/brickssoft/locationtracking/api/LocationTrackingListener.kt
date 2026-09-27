// CONTRACT (scaffold, round 2): signatures fixed by docs/e2e/architecture.md §2. Owned by Unit 5 (Companion native
// API) for KDoc and behavior only; do not change the signatures.
package com.brickssoft.locationtracking.api

import android.content.Context
import org.json.JSONObject

/**
 * Receives every queued record and every tracking event natively, also in processes where no WebView (and no JS)
 * exists: after a reboot, a heartbeat alarm or a `START_STICKY` restart.
 *
 * Register it in one of two ways:
 * - **Manifest** (recommended for a companion plugin that must never miss an event): a `<meta-data>` inside
 *   `<application>` whose name is [LocationTrackingNative.LISTENER_META_DATA] (or starts with it followed by `.`, for
 *   a second listener) and whose value is the fully qualified class name. The class needs a public no-arg
 *   constructor. It is instantiated once per process in `Components.bootstrap()`, which every entry point runs
 *   (plugin load, service, receivers) before the engine can emit anything.
 * - **Programmatically**: [LocationTrackingNative.addListener]; receives only what is emitted after the call.
 *
 * Methods run on the plugin's single `LT-native` background thread, in emission order (a record's [onRecord] comes
 * before the events that carry it). Exceptions are caught and logged. Delivery never blocks the engine; keep the
 * methods short and hand long work (I/O, uploads) to your own executor.
 */
interface LocationTrackingListener {
    /**
     * Every record queued for upload, including `tracking_start`/`tracking_stop`, `providerchange`, `heartbeat`,
     * `geofence` and `insertLocation()` records: the wire JSON of `RecordJson.toJson(record)` (no `sent_at`).
     */
    fun onRecord(context: Context, record: JSONObject) {}

    /** Every JS event, with the same name and payload as the JS `addListener` API (`EventJson.name` / `payload`). */
    fun onEvent(context: Context, name: String, payload: JSONObject) {}
}

/** Returned by [LocationTrackingNative.addListener]. Idempotent. */
fun interface NativeSubscription {
    fun remove()
}

/**
 * Result of a [LocationTrackingNative] call, always invoked on the `LT-native` thread. A failure carries a
 * `com.brickssoft.locationtracking.core.TrackingException` whose `code` is the JS `ErrorCode`.
 */
fun interface NativeCallback<T> {
    fun onResult(result: Result<T>)
}
