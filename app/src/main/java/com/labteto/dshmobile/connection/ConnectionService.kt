package com.labteto.dshmobile.connection

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.labteto.dshmobile.MainActivity
import com.labteto.dshmobile.R
import com.labteto.dshmobile.diag.BackgroundTrace
import com.labteto.dshmobile.notify.DshNotifications
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service that keeps the WebSocket connection alive while the app
 * is backgrounded (user opt-in via settings). The ConnectionManager owns the
 * actual connection; this service only pins the process with an ongoing
 * notification while it is running.
 */
@AndroidEntryPoint
class ConnectionService : Service() {

    @Inject lateinit var connectionManager: ConnectionManager
    @Inject lateinit var notifications: DshNotifications
    @Inject lateinit var trace: BackgroundTrace

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            trace.record(BackgroundTrace.Kind.SERVICE, "stop")
            stopSelf()
            return START_NOT_STICKY
        }
        // `restored` is the START_STICKY case after the process was killed: the system brings the
        // service back with no intent, and the notification below is built from a ConnectionManager
        // that has no host. Worth recording, because it looks like a live connection from outside.
        trace.record(BackgroundTrace.Kind.SERVICE, if (intent == null) "restored" else "start")
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        // The service dying does not tear the connection down; the manager owns it.
        trace.record(BackgroundTrace.Kind.SERVICE, "destroyed")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val host = connectionManager.state.value.host
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = host?.let { getString(R.string.notif_connected_text, it.authority) }
            ?: getString(R.string.common_connected)
        return NotificationCompat.Builder(this, DshNotifications.CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification_whale)
            .setContentTitle(getString(R.string.notif_connected_title))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.labteto.dshmobile.connection.STOP"
        private const val NOTIFICATION_ID = 10
    }
}
