// STUB — owned by Unit 13 (PremiseMonitor fake plugin).
package com.brickssoft.premisemonitor

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * PremiseMonitor's own foreground service (type location), running while the device is inside the monitored premise
 * (started on ENTER, stopped on EXIT or stopMonitoring). Never started by the scaffold stub.
 */
class PremiseMonitorService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
