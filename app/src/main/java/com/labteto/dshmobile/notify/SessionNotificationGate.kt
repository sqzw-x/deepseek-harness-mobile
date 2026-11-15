package com.labteto.dshmobile.notify

import com.labteto.dshmobile.core.notify.CompletionEvent
import com.labteto.dshmobile.data.SessionRow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** A fork has a parent too; only a subagent origin or a retained child identity proves a child. */
internal enum class NotificationSessionKind { ORDINARY, SUBAGENT, UNKNOWN }

/** Retain positive child identity when an added frame supplies fewer header fields. */
internal fun mergeNotificationMetadata(
    held: Pair<SessionRow, Boolean>?,
    row: SessionRow,
    listed: Boolean,
): Pair<SessionRow, Boolean> {
    val child = held?.first?.takeIf { it.origin == "subagent" || it.subagent != null }
    return (child ?: row) to (listed || held?.second == true)
}

internal fun notificationSessionKind(row: SessionRow?, listed: Boolean): NotificationSessionKind = when {
    row == null -> NotificationSessionKind.UNKNOWN
    row.origin == "subagent" || row.subagent != null -> NotificationSessionKind.SUBAGENT
    listed || row.origin != null -> NotificationSessionKind.ORDINARY
    else -> NotificationSessionKind.UNKNOWN
}

/**
 * Completion noise belongs to ordinary sessions, but a child's approval/question still needs help.
 *
 * Host status can beat session metadata at startup or creation. Resolve that race before deciding,
 * retaining the already classified event rather than consuming its running→idle transition again.
 * A read or foreign answer invalidates an in-flight decision, as does changing host/connection.
 */
internal class SessionNotificationGate(
    private val connectionKey: () -> String?,
    private val sessionKind: (String) -> NotificationSessionKind,
    private val refreshSessions: suspend () -> Unit,
    private val unresolved: (String) -> Unit = {},
) {
    private val refreshMutex = Mutex()
    private val lock = Any()
    private val dismissed = LinkedHashMap<String, Long>()
    private var serial = 0L

    fun dismiss(sessionId: String) = synchronized(lock) {
        dismissed[sessionId] = ++serial
        while (dismissed.size > 1024) dismissed.remove(dismissed.keys.first())
    }

    data class Ticket(val connection: String?, val serial: Long)

    fun ticket(): Ticket = synchronized(lock) { Ticket(connectionKey(), serial) }

    fun isCurrent(sessionId: String, ticket: Ticket): Boolean =
        ticket.connection != null && connectionKey() == ticket.connection && synchronized(lock) {
            (dismissed[sessionId] ?: 0L) <= ticket.serial
        }

    suspend fun allows(event: CompletionEvent, ticket: Ticket = ticket()): Boolean {
        // Never silence a request that can leave a child waiting for a human response.
        if (event is CompletionEvent.ReviewRequested || event is CompletionEvent.QuestionRequested) return true
        fun current(): Boolean = isCurrent(event.sessionId, ticket)
        fun decision(): Boolean? = when (sessionKind(event.sessionId)) {
            NotificationSessionKind.ORDINARY -> true
            NotificationSessionKind.SUBAGENT -> false
            NotificationSessionKind.UNKNOWN -> null
        }
        if (!current()) return false
        decision()?.let { return it }
        // A missing row is not evidence of an ordinary session. Coalesce concurrent lookups and
        // retry a failed/early baseline, without ever posting unverified child completions.
        repeat(2) {
            refreshMutex.withLock {
                if (current() && decision() == null) withTimeoutOrNull(5_000) { refreshSessions() }
            }
            if (!current()) return false
            decision()?.let { return it }
        }
        unresolved(event.sessionId)
        return false
    }
}
