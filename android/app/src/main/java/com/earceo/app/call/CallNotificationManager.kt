package com.earceo.app.call

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.os.Build
import com.earceo.app.MainActivity
import com.earceo.app.R

class CallNotificationManager(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.call_notification_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun showOngoingCall() {
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hangupIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).setAction(ACTION_END_CALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            Notification.Builder(context)
        }
        builder
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.call_notification_title))
            .setContentText(context.getString(R.string.call_notification_text))
            .setContentIntent(openIntent)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(
                Notification.CallStyle.forOngoingCall(
                    Person.Builder()
                        .setName(context.getString(R.string.app_name))
                        .setImportant(true)
                        .build(),
                    hangupIntent,
                ),
            )
        } else {
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.end_livekit_call),
                    hangupIntent,
                ).build(),
            )
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    fun cancel() {
        manager.cancel(NOTIFICATION_ID)
    }

    companion object {
        const val ACTION_END_CALL = "com.earceo.app.action.END_CALL"
        private const val CHANNEL_ID = "earceo_calls"
        private const val NOTIFICATION_ID = 2001
    }
}
