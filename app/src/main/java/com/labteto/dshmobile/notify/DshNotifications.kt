package com.labteto.dshmobile.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.labteto.dshmobile.MainActivity
import com.labteto.dshmobile.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification channels + post helpers. Completion events come from the
 * core CompletionClassifier (turn end, goal complete/blocked, plan review,
 * approval/question requested).
 */
@Singleton
class DshNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_COMPLETIONS, context.getString(R.string.notif_channel_completions), NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ACTION, context.getString(R.string.notif_channel_action), NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTION, context.getString(R.string.notif_channel_connection), NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * Post a completion/action notification that deep-links to a session.
     * [canPost] gates the POST_NOTIFICATIONS runtime permission (API 33+);
     * the lint suppression covers that checked gate.
     *
     * The session is the notification *tag* and the channel picks its slot, so two sessions can
     * never replace or cancel each other no matter how many are open. Everything about the
     * notification is derivable from the session id, which is what lets [cancelSession] work from
     * a process that never posted it.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun postSession(
        channel: String,
        title: String,
        text: String,
        sessionId: String,
        actionLabel: String? = null,
    ) {
        if (!canPost()) return
        val slot = slotFor(channel)
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = android.net.Uri.parse("dshmobile://host/current/session/$sessionId")
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
        val pending = PendingIntent.getActivity(
            context,
            slot,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification_whale)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(if (channel == CHANNEL_ACTION) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
        if (actionLabel != null) {
            builder.addAction(0, actionLabel, pending)
        }
        NotificationManagerCompat.from(context).notify(sessionId, slot, builder.build())
    }

    /**
     * Withdraw everything this app posted for [sessionId] — in either channel, and whether or not
     * the session's own notification was ever tapped.
     *
     * No bookkeeping: [postSession] derives the tag and slot from the session id alone, so this
     * also clears notifications posted by a process that has since died. Cancelling what is not
     * showing is a no-op, and it needs no POST_NOTIFICATIONS grant — the permission gates posting
     * only.
     */
    fun cancelSession(sessionId: String) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(sessionId, SLOT_COMPLETIONS)
        manager.cancel(sessionId, SLOT_ACTION)
    }

    /** Notification id inside a session tag, i.e. which of a session's notifications this is. */
    private fun slotFor(channel: String): Int =
        if (channel == CHANNEL_ACTION) SLOT_ACTION else SLOT_COMPLETIONS

    companion object {
        const val CHANNEL_COMPLETIONS = "completions"
        const val CHANNEL_ACTION = "needs_action"
        const val CHANNEL_CONNECTION = "connection"
        const val EXTRA_SESSION_ID = "dsh_session_id"

        private const val SLOT_COMPLETIONS = 1
        private const val SLOT_ACTION = 2
    }
}
