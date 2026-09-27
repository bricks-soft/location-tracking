package com.brickssoft.locationtracking.provider.android

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.location.LocationManagerCompat
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.TrackingException
import com.brickssoft.locationtracking.model.DesiredAccuracy

/** Provider choice and last-known lookups over the platform [LocationManager]. */
internal object LocationProviders {
    /** `LocationManager.FUSED_PROVIDER` (public since API 31). */
    private const val FUSED_PROVIDER = "fused"

    /**
     * Providers for [accuracy], most preferred first: HIGH prefers gps, BALANCED/LOW prefer network, each falling back
     * to the other (then `fused` on API 31+); PASSIVE uses only the passive provider.
     */
    fun preferred(accuracy: DesiredAccuracy): List<String> {
        val fused = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(FUSED_PROVIDER) else emptyList()
        return when (accuracy) {
            DesiredAccuracy.HIGH -> listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER) + fused
            DesiredAccuracy.BALANCED, DesiredAccuracy.LOW ->
                listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER) + fused
            DesiredAccuracy.PASSIVE -> listOf(LocationManager.PASSIVE_PROVIDER)
        }
    }

    /**
     * The providers to try for [accuracy], in order: the enabled ones of [preferred], then (unless [enabledOnly]) the
     * existing but disabled ones; a request on a disabled provider starts delivering once it is enabled. If none of
     * them exists on the device, the passive provider is the last resort.
     */
    fun candidates(lm: LocationManager, accuracy: DesiredAccuracy, enabledOnly: Boolean = false): List<String> {
        val existing = preferred(accuracy).filter { exists(lm, it) }
        if (existing.isEmpty()) {
            val passive = LocationManager.PASSIVE_PROVIDER
            return if (accuracy != DesiredAccuracy.PASSIVE && exists(lm, passive)) listOf(passive) else emptyList()
        }
        val (enabled, disabled) = existing.partition { isEnabled(lm, it) }
        return if (enabledOnly) enabled else enabled + disabled
    }

    fun exists(lm: LocationManager, provider: String): Boolean =
        try {
            LocationManagerCompat.hasProvider(lm, provider)
        } catch (e: Exception) {
            false
        }

    fun isEnabled(lm: LocationManager, provider: String): Boolean =
        try {
            lm.isProviderEnabled(provider)
        } catch (e: Exception) {
            false
        }

    /**
     * The freshest `getLastKnownLocation` across the enabled providers, or null if none has a fix.
     *
     * @throws TrackingException [ErrorCode.PERMISSION_DENIED] if every provider refused with a [SecurityException].
     */
    @SuppressLint("MissingPermission")
    fun freshestLastKnown(lm: LocationManager): Location? {
        val providers = try {
            lm.getProviders(true)
        } catch (e: Exception) {
            emptyList<String>()
        }
        var best: Location? = null
        var denied: SecurityException? = null
        var permitted = false
        for (provider in providers) {
            val location = try {
                lm.getLastKnownLocation(provider).also { permitted = true }
            } catch (e: SecurityException) {
                denied = e
                continue
            } catch (e: IllegalArgumentException) {
                continue
            }
            if (location != null && (best == null || isNewer(location, best))) best = location
        }
        if (!permitted && denied != null) {
            throw TrackingException(ErrorCode.PERMISSION_DENIED, "Location permission denied", denied)
        }
        return best
    }

    /** Compares on the elapsed-realtime clock when both fixes carry it, else on wall time. */
    fun isNewer(a: Location, b: Location): Boolean {
        val an = a.elapsedRealtimeNanos
        val bn = b.elapsedRealtimeNanos
        return if (an > 0 && bn > 0) an > bn else a.time > b.time
    }
}
