package com.brickssoft.locationtracking.service

/** Starts and stops the location foreground service. */
interface ServiceController {
    val isRunning: Boolean

    /** Returns false if the OS refused to start the service (logged). */
    fun start(): Boolean

    fun stop()

    /** Rebuilds the notification from the current config. */
    fun refreshNotification()

    /**
     * Posts the resume notification (`notification.resume`), dismissed at [deadline] (epoch ms) if not null. Only logs
     * when notifications are not allowed (`POST_NOTIFICATIONS`).
     */
    fun showResumeNotification(deadline: Long?) = Unit

    fun cancelResumeNotification() = Unit
}
