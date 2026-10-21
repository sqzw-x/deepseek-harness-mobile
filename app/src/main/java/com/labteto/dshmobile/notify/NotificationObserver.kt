package com.labteto.dshmobile.notify

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.AppSettings
import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.core.notify.CompletionClassifier
import com.labteto.dshmobile.core.notify.CompletionEvent
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.wire.dto.RemoteEventFrame
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.diag.BackgroundTrace
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns completion/needs-action wire signals into local notifications.
 *
 * `start()` launches one collector on the connection manager's host event stream; call it once
 * from [android.app.Application.onCreate] after the store is initialized. Notifications are tagged
 * by session and slotted by channel, so re-notifying the same session replaces the previous one
 * instead of stacking, and [DshNotifications.cancelSession] can withdraw a session's notifications
 * without this class remembering what it posted.
 *
 * Turn and goal completions are not seen here any more. Through 0.1.1 they arrived as
 * `session/event` frames on the all-session mux, which this observer read directly; 0.1.2 carries
 * session events only on a per-session `session/follow` stream, so [SessionStore] — which owns
 * those streams — reports them through [onSessionEvent] instead. What remains on the host event
 * stream is the two pending-request waterfalls and the session status notification.
 */
@Singleton
class NotificationObserver @Inject constructor(
    private val store: SessionStore,
    private val connectionManager: ConnectionManager,
    private val notifications: DshNotifications,
    private val hostsStore: HostsStore,
    private val trace: BackgroundTrace,
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val classifier = CompletionClassifier()
    private val seen = LinkedHashSet<String>()

    @Volatile
    private var settings = AppSettings()

    @Volatile
    private var started = false

    /**
     * Whether any activity is currently started. Suppression keys off "the user is looking
     * at this session", which is the session being open *and* the app being in the
     * foreground: `SessionStore.currentSessionId` never clears once set, so without this
     * flag a session opened once would stay silenced for the rest of the process even
     * while the app sits in the background.
     */
    @Volatile
    private var appInForeground = false

    /**
     * Whether an activity is actually on screen, as opposed to merely started.
     *
     * Withdrawing a session's notifications keys off *this* and not [appInForeground]: an
     * activity started to handle a notification tap has not yet been handed the intent that
     * names the session to open — `onNewIntent` lands before `onResume` — so acting at start
     * would withdraw the notification of the session being left instead of the destination's.
     */
    private val resumed = MutableStateFlow(false)

    fun start() {
        if (started) return
        started = true
        notifications.ensureChannels()
        trackAppForeground()
        clearReadNotifications()
        scope.launch {
            hostsStore.settings.collect { settings = it }
        }
        scope.launch {
            connectionManager.eventFrames.collect { handleEventFrame(it) }
        }
    }

    /**
     * Keep [appInForeground] and [resumed] in step with the activity stack. `start()` runs from
     * `Application.onCreate`, before any activity exists, so counting start/stop and
     * resume/pause pairs is exact from the first activity on.
     */
    private fun trackAppForeground() {
        (context as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                private var startedActivities = 0
                private var resumedActivities = 0

                override fun onActivityStarted(activity: Activity) {
                    if (++startedActivities == 1) {
                        appInForeground = true
                        trace.record(BackgroundTrace.Kind.FOREGROUND, "")
                    }
                }

                override fun onActivityResumed(activity: Activity) {
                    if (++resumedActivities == 1) resumed.value = true
                }

                override fun onActivityPaused(activity: Activity) {
                    if (--resumedActivities == 0) resumed.value = false
                }

                override fun onActivityStopped(activity: Activity) {
                    if (--startedActivities == 0) {
                        appInForeground = false
                        trace.record(BackgroundTrace.Kind.BACKGROUND, "")
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            },
        )
    }

    /**
     * Withdraw a session's notifications as soon as the app is showing that session.
     *
     * A tap on a notification cancels that one notification itself; this covers the way in that
     * the shade never learns about — switching sessions inside the app, and coming back to a
     * session that stayed open the whole time the app was in the background. Both are "the user
     * has seen this session", so neither should leave anything behind in the shade.
     *
     * All three inputs matter. A pending reveal is a tap in flight: at that instant the session on
     * screen is still the one being left, so clearing it would drop a notification for a session
     * nobody looked at; the destination is cleared once the store has opened it and dropped the
     * reveal. Nothing is filtered to distinct sessions, because returning to the foreground with
     * the same session open is exactly a case worth clearing.
     */
    private fun clearReadNotifications() {
        scope.launch {
            combine(
                resumed,
                store.currentSessionId,
                store.pendingReveal,
            ) { onScreen, sessionId, reveal ->
                sessionId.takeIf { onScreen && reveal == null }
            }.filterNotNull().collect { notifications.cancelSession(it) }
        }
    }

    private fun handleEventFrame(frame: RemoteEventFrame) {
        when (frame) {
            is RemoteEventFrame.Waterfall ->
                classifier.classifyWaterfall(frame.event, frame.eventId, frame.agentId, frame.request)
                    ?.let { maybeNotify(it) }

            is RemoteEventFrame.Emit ->
                classifier.classifyNotification(frame.event, frame.args)?.let { maybeNotify(it) }

            // A withdrawn request needs no notification of its own; the prompt it belongs to is
            // retired by whoever is showing it. Ready never reaches here, and an unknown frame
            // kind is exactly what this observer should ignore.
            is RemoteEventFrame.Cancel,
            is RemoteEventFrame.Ready,
            is RemoteEventFrame.Unknown,
            -> Unit
        }
    }

    /**
     * One session event, forwarded by [SessionStore] from that session's follow stream.
     *
     * The observer cannot subscribe to these itself: 0.1.2 has no all-session event stream, and
     * opening one follow per session purely to watch for completions would resume agents the user
     * never opened.
     */
    fun onSessionEvent(sessionId: String, envelope: SessionEventEnvelope) {
        when (envelope.type) {
            "turn/start" -> classifier.markSessionRunning(sessionId)
            "turn/end", "goal/change" ->
                classifier.classifyEvent(sessionId, envelope)?.let { maybeNotify(it) }
        }
    }

    private fun maybeNotify(event: CompletionEvent) {
        if (!notifications.canPost()) return
        // Suppress only the completion the user can already see happening.
        if (appInForeground && store.isSessionOpen(event.sessionId)) return

        val spec = when (event) {
            is CompletionEvent.TurnComplete -> {
                if (!settings.notifyTurnComplete) return
                Spec(
                    channel = DshNotifications.CHANNEL_COMPLETIONS,
                    title = context.getString(R.string.notif_turn_complete, sessionTitle(event.sessionId).orEmpty()),
                )
            }
            is CompletionEvent.GoalComplete -> {
                if (!settings.notifyGoal) return
                Spec(
                    channel = DshNotifications.CHANNEL_COMPLETIONS,
                    title = context.getString(R.string.notif_goal_complete, event.objective.orEmpty()),
                )
            }
            is CompletionEvent.GoalBlocked -> {
                if (!settings.notifyGoal) return
                Spec(
                    channel = DshNotifications.CHANNEL_COMPLETIONS,
                    title = context.getString(R.string.notif_goal_blocked, event.reason.orEmpty()),
                )
            }
            is CompletionEvent.ReviewRequested -> {
                if (!settings.notifyNeedsAction) return
                Spec(
                    channel = DshNotifications.CHANNEL_ACTION,
                    title = context.getString(R.string.notif_review_requested, event.toolName),
                    actionLabel = context.getString(R.string.notif_open),
                )
            }
            is CompletionEvent.QuestionRequested -> {
                if (!settings.notifyNeedsAction) return
                Spec(
                    channel = DshNotifications.CHANNEL_ACTION,
                    title = context.getString(R.string.notif_question, event.firstQuestion.orEmpty()),
                )
            }
            is CompletionEvent.SessionIdle -> {
                if (!settings.notifyTurnComplete) return
                Spec(
                    channel = DshNotifications.CHANNEL_COMPLETIONS,
                    title = context.getString(R.string.notif_session_idle, sessionTitle(event.sessionId).orEmpty()),
                )
            }
        }

        if (isDuplicate(event.dedupKey)) return

        val text = context.getString(R.string.notif_open)
        notifications.postSession(spec.channel, spec.title, text, event.sessionId, spec.actionLabel)
    }

    private data class Spec(
        val channel: String,
        val title: String,
        val actionLabel: String? = null,
    )

    private fun sessionTitle(sessionId: String): String? =
        store.sessions.value.firstOrNull { it.sessionId == sessionId }?.title

    private fun isDuplicate(key: String): Boolean {
        synchronized(seen) {
            if (key in seen) return true
            seen.add(key)
            while (seen.size > MAX_DEDUP_KEYS) {
                val it = seen.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                } else {
                    break
                }
            }
            return false
        }
    }

    private companion object {
        const val MAX_DEDUP_KEYS = 1024
    }
}
