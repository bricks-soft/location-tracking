package com.brickssoft.locationtracking.provider

import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.TrackedLocation

/**
 * One OS location registration made by a backend for one listener. [start] registers it and reports the outcome
 * through `done` (null = registered, else the failure), on any thread, possibly before [start] returns. [remove]
 * unregisters it; removing a registration that never completed is harmless. Neither may throw.
 */
internal interface OsRegistration {
    fun start(done: (Exception?) -> Unit)

    fun remove()
}

/** What the OS callbacks of one [OsRegistration] report. Any thread. */
internal interface RegistrationEvents {
    fun locations(locations: List<TrackedLocation>)

    /** `LocationAvailability` from GMS or HMS: a hint, which Google documents can be wrong in both directions. */
    fun availability(available: Boolean)

    /** A failure reported after the registration completed (the platform backend's providers, for example). */
    fun error(error: Exception)
}

/**
 * Listener bookkeeping shared by the GMS, HMS and Android location backends (recommendation 3), in place of three
 * copies of it. One subscription per [LocationListener]:
 * - [request] creates a new OS registration; a request for a listener that has one replaces it (the previous one is
 *   removed first, and its late fixes are dropped).
 * - **Registration result.** A failure (synchronous or asynchronous) forgets the subscription, removes the OS
 *   registration and reports the error to the listener once ([LocationListener.onError], mapped by [mapError]); the
 *   engine then retries. A delivered fix counts as a successful registration: Huawei does not guarantee that the
 *   success callback comes before the first result, and without AppGallery Connect setup it can come late or never. A
 *   result reported after that (or a second one) is ignored.
 * - **Removal.** [remove] stops deliveries at once. If the OS registration has not completed yet, it is removed now
 *   (best effort) and again once it completes, so a late registration cannot keep running unseen.
 * - Listener exceptions are logged, never thrown into the OS callback.
 *
 * Unlike the design it follows, an error does not stop tracking: the listener decides what to do.
 */
internal class LocationSubscriptions(
    private val tag: String,
    private val mapError: (Exception) -> TrackingException,
) {
    private val lock = Any()
    private val subscriptions = HashMap<LocationListener, Subscription>()

    /** The last `LocationAvailability` reported by any registration, or null if none was reported yet. */
    @Volatile
    var availabilityHint: Boolean? = null
        private set

    /** Number of listeners with a subscription (registered or pending). */
    val count: Int get() = synchronized(lock) { subscriptions.size }

    /** The listeners that have a subscription, and the request each was made with. */
    fun requests(): Map<LocationListener, LocationRequestSpec> =
        synchronized(lock) { subscriptions.mapValues { it.value.spec } }

    /** Registers [listener] for [spec] through the registration [create] builds (see the class docs). */
    fun request(listener: LocationListener, spec: LocationRequestSpec, create: (RegistrationEvents) -> OsRegistration) {
        val subscription = Subscription(listener, spec)
        val previous = synchronized(lock) { subscriptions.put(listener, subscription) }
        previous?.cancel()
        val registration = try {
            create(subscription)
        } catch (e: Exception) {
            subscription.registered(e)
            return
        }
        subscription.begin(registration)
    }

    /** Removes [listener]'s subscription; unknown listeners are ignored. */
    fun remove(listener: LocationListener) {
        synchronized(lock) { subscriptions.remove(listener) }?.cancel()
    }

    private fun forget(subscription: Subscription) {
        synchronized(lock) {
            if (subscriptions[subscription.listener] === subscription) subscriptions.remove(subscription.listener)
        }
    }

    private inner class Subscription(val listener: LocationListener, val spec: LocationRequestSpec) : RegistrationEvents {
        private val subscriptionLock = Any()
        private var registration: OsRegistration? = null
        private var active = true

        /** The outcome of the OS registration is known (completed, failed, or a fix was delivered). */
        private var finished = false

        fun begin(registration: OsRegistration) {
            synchronized(subscriptionLock) { this.registration = registration }
            try {
                registration.start(::registered)
            } catch (e: Exception) {
                registered(e)
            }
        }

        fun registered(error: Exception?) {
            val report: TrackingException?
            val removeNow: Boolean
            synchronized(subscriptionLock) {
                if (finished) return
                finished = true
                report = if (error != null && active) mapError(error) else null
                if (error != null) active = false
                // Removed while the registration was pending: now that it exists, remove it for good.
                removeNow = error != null || !active
            }
            if (error != null) forget(this)
            if (removeNow) removeRegistration()
            if (report != null) {
                Logger.e(tag, "location request failed: ${report.code}: ${report.message}", error)
                notifyError(report)
            }
        }

        override fun locations(locations: List<TrackedLocation>) {
            if (locations.isEmpty()) return
            synchronized(subscriptionLock) {
                if (!active) return
                finished = true // a delivered fix proves the registration (HMS may never confirm it)
            }
            try {
                listener.onLocations(locations)
            } catch (e: Exception) {
                Logger.e(tag, "location listener failed", e)
            }
        }

        override fun availability(available: Boolean) {
            synchronized(subscriptionLock) { if (!active) return }
            if (availabilityHint != available) Logger.i(tag, "location availability (hint): $available")
            availabilityHint = available
        }

        override fun error(error: Exception) {
            synchronized(subscriptionLock) {
                if (!active) return
                active = false
                finished = true
            }
            forget(this)
            removeRegistration()
            val mapped = mapError(error)
            Logger.e(tag, "location updates failed: ${mapped.code}: ${mapped.message}", error)
            notifyError(mapped)
        }

        /** Stops deliveries; removes the OS registration now and, if it is still pending, again when it completes. */
        fun cancel() {
            synchronized(subscriptionLock) { active = false }
            removeRegistration()
        }

        private fun removeRegistration() {
            val target = synchronized(subscriptionLock) { registration } ?: return
            try {
                target.remove()
            } catch (e: Exception) {
                Logger.w(tag, "removing a location registration failed", e)
            }
        }

        private fun notifyError(error: TrackingException) {
            try {
                listener.onError(error)
            } catch (e: Exception) {
                Logger.e(tag, "location listener failed on an error", e)
            }
        }
    }
}
