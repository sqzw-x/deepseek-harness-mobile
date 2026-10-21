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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
 * stream is the two pending-request waterfalls, the session status notification, and the two
 * shared facts that let a notification be withdrawn from another client's doing.
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

    /**
     * Pending-request id → the session it belongs to, for the waterfalls this observer was told
     * about.
     *
     * A [RemoteEventFrame.Cancel] names only the request, and the settlement may come from another
     * client, so the id has to be resolved back to a session here. Unlike [seen], this map is
     * touched on the frame collector only; [onSessionEvent] arrives from a session's own stream.
     */
    private val pendingRequests = LinkedHashMap<String, String>()

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
            is RemoteEventFrame.Waterfall -> {
                val event = classifier.classifyWaterfall(frame.event, frame.eventId, frame.agentId, frame.request)
                if (event != null) {
                    rememberPending(frame.eventId, event.sessionId)
                    maybeNotify(event)
                }
            }

            is RemoteEventFrame.Emit -> when (frame.event) {
                // A human prompt admitted in some session, from any client — this phone included.
                // Whoever sent it is looking at that session, so a notification about it is spent.
                EVENT_SESSION_ACTIVITY -> frame.args.sessionId()?.let(::handledElsewhere)
                else -> classifier.classifyNotification(frame.event, frame.args)?.let { maybeNotify(it) }
            }

            // Another client answered the request a notification was about, or the host's own
            // caller cancelled it: either way the question is gone and its notification with it.
            is RemoteEventFrame.Cancel -> takePending(frame.eventId)?.let(::handledElsewhere)

            // Ready never carries a notification, and an unknown frame kind is exactly what this
            // observer should ignore.
            is RemoteEventFrame.Ready,
            is RemoteEventFrame.Unknown,
            -> Unit
        }
    }

    /**
     * Withdraw a session's notifications because the user is demonstrably done with them
     * somewhere else.
     *
     * The host has no read receipt — merely opening a session on the web produces nothing on the
     * wire, and adding one would take a host change — so this fires on the two weaker facts that
     * *are* shared between clients: a human prompt in that session, and a pending request settled
     * by whoever answered first. A notification whose question was answered elsewhere is exactly
     * as stale as one about a session being looked at, so both withdraw the session's lot.
     */
    private fun handledElsewhere(sessionId: String) {
        notifications.cancelSession(sessionId)
    }

    /** Remember which session a pending request belongs to; a later [RemoteEventFrame.Cancel] names only the request. */
    private fun rememberPending(eventId: String, sessionId: String) {
        pendingRequests[eventId] = sessionId
        // A cancel can be lost with the connection that owed it, so the map is bounded rather
        // than trusted to empty itself.
        while (pendingRequests.size > MAX_PENDING_REQUESTS) {
            val oldest = pendingRequests.keys.firstOrNull() ?: break
            pendingRequests.remove(oldest)
        }
    }

    private fun takePending(eventId: String): String? = pendingRequests.remove(eventId)

    /** The session an `api-session/activity` emit names; null when the frame carries no usable id. */
    private fun List<JsonElement>.sessionId(): String? =
        (firstOrNull() as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

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
        const val MAX_PENDING_REQUESTS = 128

        /** Harness 0.1.7+: a human prompt was admitted in that session, by some client. */
        const val EVENT_SESSION_ACTIVITY = "api-session/activity"
    }
}
