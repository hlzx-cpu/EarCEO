package com.earceo.app.call

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Supplies the foreground execution identity required for CallStyle
 * notifications and microphone/WebRTC work on modern Android.
 *
 * Media ownership will move into this service in the next hardening step.
 * For now it deliberately has no binder surface; MainActivity sends only
 * explicit start/stop intents.
 */
class EarCeoCallService : Service() {
    private lateinit var notifications: CallNotificationManager

    override fun onCreate() {
        super.onCreate()
        notifications = CallNotificationManager(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                startForeground(
                    CallNotificationManager.NOTIFICATION_ID,
                    notifications.buildOngoingCall(),
                )
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        notifications.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.earceo.app.action.START_CALL_SERVICE"
        const val ACTION_STOP = "com.earceo.app.action.STOP_CALL_SERVICE"
    }
}
