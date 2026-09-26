// STUB — owned by Unit 8 (Service). Replace this implementation.
package com.brickssoft.locationtracking.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** The location foreground service. Stub: stops itself immediately. */
class LocationTrackingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
