package com.labteto.dshmobile.notify

import com.labteto.dshmobile.core.notify.CompletionClassifier
import com.labteto.dshmobile.core.notify.CompletionEvent
import com.labteto.dshmobile.data.SessionRow
import com.labteto.dshmobile.data.SubagentIdentity
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class SessionNotificationGateTest {
    private fun row(origin: String? = null, parent: String? = null, child: SubagentIdentity? = null) =
        SessionRow("s", "title", false, false, parent, origin, null, null, 0, null, subagent = child)

    @Test fun `root and ordinary forks are not mistaken for subagents`() {
        assertEquals(NotificationSessionKind.ORDINARY, notificationSessionKind(row(), true))
        assertEquals(NotificationSessionKind.ORDINARY, notificationSessionKind(row(parent = "p"), true))
        assertEquals(NotificationSessionKind.ORDINARY, notificationSessionKind(row("fork", "p"), false))
    }

    @Test fun `subagent origin alone is enough even without a descriptor or parent`() {
        assertEquals(NotificationSessionKind.SUBAGENT, notificationSessionKind(row("subagent"), false))
        assertEquals(NotificationSessionKind.SUBAGENT, notificationSessionKind(row("subagent", "p"), true))
    }

    @Test fun `retained child identity survives a partial added frame`() {
        for (mode in listOf("one-shot", "continuable", "unknown")) {
            assertEquals(NotificationSessionKind.SUBAGENT,
                notificationSessionKind(row(child = SubagentIdentity("parent", mode)), false))
        }
    }

    @Test fun `partial added metadata cannot downgrade a known child or erase list provenance`() {
        val child = mergeNotificationMetadata(null, row("subagent", "parent"), false)
        val partialChild = mergeNotificationMetadata(child, row(), false)
        assertEquals(NotificationSessionKind.SUBAGENT,
            notificationSessionKind(partialChild.first, partialChild.second))
        val ordinary = mergeNotificationMetadata(null, row(parent = "fork-parent"), true)
        val partialRoot = mergeNotificationMetadata(ordinary, row(), false)
        assertEquals(NotificationSessionKind.ORDINARY,
            notificationSessionKind(partialRoot.first, partialRoot.second))
    }

    @Test fun `missing or partial metadata is unknown not an ordinary session`() {
        assertEquals(NotificationSessionKind.UNKNOWN, notificationSessionKind(null, false))
        assertEquals(NotificationSessionKind.UNKNOWN, notificationSessionKind(row(), false))
    }

    private class Harness {
        var connection: String? = "host-a|generation-1"
        var kind = NotificationSessionKind.UNKNOWN
        var refreshCount = 0
        var unresolvedCount = 0
        var refresh: suspend () -> Unit = {}
        val gate = SessionNotificationGate(
            connectionKey = { connection }, sessionKind = { kind },
            refreshSessions = { refreshCount++; refresh() }, unresolved = { unresolvedCount++ },
        )
    }

    @Test fun `every completion kind is silent for a child or grandchild`() = runTest {
        val h = Harness().apply { kind = NotificationSessionKind.SUBAGENT }
        for (event in listOf(
            CompletionEvent.SessionIdle("child", 1), CompletionEvent.TurnComplete("child", 2, 1),
            CompletionEvent.GoalComplete("grandchild", 3, "work"), CompletionEvent.GoalBlocked("grandchild", 4, "blocked"),
        )) assertFalse(h.gate.allows(event))
        assertEquals(0, h.refreshCount)
    }

    @Test fun `an ordinary session keeps consecutive completion events`() = runTest {
        val h = Harness().apply { kind = NotificationSessionKind.ORDINARY }
        assertTrue(h.gate.allows(CompletionEvent.SessionIdle("s", 1)))
        assertTrue(h.gate.allows(CompletionEvent.SessionIdle("s", 2)))
        assertEquals(0, h.refreshCount)
    }

    @Test fun `the first root idle survives status arriving before its list row`() = runTest {
        val classifier = CompletionClassifier()
        classifier.classifyNotification("api-session/status", listOf(JsonPrimitive("root"), JsonPrimitive(true)))
        val idle = classifier.classifyNotification("api-session/status", listOf(JsonPrimitive("root"), JsonPrimitive(false)))!!
        val h = Harness().apply { refresh = { kind = NotificationSessionKind.ORDINARY } }
        assertTrue(h.gate.allows(idle))
        assertEquals(1, h.refreshCount)
        assertNull(classifier.classifyNotification("api-session/status", listOf(JsonPrimitive("root"), JsonPrimitive(false))))
    }

    @Test fun `a fast child completion waits for its identity instead of leaking`() = runTest {
        val h = Harness().apply { refresh = { kind = NotificationSessionKind.SUBAGENT } }
        assertFalse(h.gate.allows(CompletionEvent.SessionIdle("new-child", 1)))
        assertEquals(1, h.refreshCount)
    }

    @Test fun `an early unsuccessful lookup can recover the root on retry`() = runTest {
        val h = Harness()
        h.refresh = { if (h.refreshCount == 2) h.kind = NotificationSessionKind.ORDINARY }
        assertTrue(h.gate.allows(CompletionEvent.SessionIdle("root", 1)))
        assertEquals(2, h.refreshCount)
    }

    @Test fun `unresolved metadata is bounded and traced rather than guessed as root`() = runTest {
        val h = Harness()
        assertFalse(h.gate.allows(CompletionEvent.SessionIdle("unknown", 1)))
        assertEquals(2, h.refreshCount)
        assertEquals(1, h.unresolvedCount)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `a stalled metadata lookup cannot leave notifications waiting forever`() = runTest {
        val h = Harness().apply { refresh = { kotlinx.coroutines.awaitCancellation() } }
        assertFalse(h.gate.allows(CompletionEvent.SessionIdle("unknown", 1)))
        assertEquals(10_000L, testScheduler.currentTime)
        assertEquals(2, h.refreshCount)
        assertEquals(1, h.unresolvedCount)
    }

    @Test fun `an approval or question still alerts even for a child`() = runTest {
        val h = Harness().apply { kind = NotificationSessionKind.SUBAGENT }
        assertTrue(h.gate.allows(CompletionEvent.ReviewRequested("child", 0, "approval", "bash", "reason")))
        assertTrue(h.gate.allows(CompletionEvent.QuestionRequested("child", 0, "question", "which?")))
        assertEquals(0, h.refreshCount)
    }

    @Test fun `reading or answering elsewhere cancels an in flight completion`() = runTest {
        val h = Harness()
        val refreshing = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        h.refresh = { refreshing.complete(Unit); resume.await(); h.kind = NotificationSessionKind.ORDINARY }
        val allowed = async { h.gate.allows(CompletionEvent.SessionIdle("s", 1)) }
        refreshing.await()
        h.gate.dismiss("s")
        resume.complete(Unit)
        assertFalse(allowed.await())
        assertTrue(h.gate.allows(CompletionEvent.SessionIdle("s", 2)))
    }

    @Test fun `a different session being read does not cancel this notification`() = runTest {
        val h = Harness().apply { refresh = { kind = NotificationSessionKind.ORDINARY } }
        val ticket = h.gate.ticket()
        h.gate.dismiss("other")
        assertTrue(h.gate.allows(CompletionEvent.SessionIdle("s", 1), ticket))
    }

    @Test fun `host switch or reconnect invalidates an old completion`() = runTest {
        for (next in listOf("host-b|generation-2", "host-a|generation-2", null)) {
            val h = Harness()
            h.refresh = { h.connection = next; h.kind = NotificationSessionKind.ORDINARY }
            assertFalse(h.gate.allows(CompletionEvent.SessionIdle("s", 1)))
        }
    }

    @Test fun `a ticket is invalidated even if read happens before the coroutine starts`() = runTest {
        val h = Harness().apply { kind = NotificationSessionKind.ORDINARY }
        val ticket = h.gate.ticket()
        h.gate.dismiss("s")
        assertFalse(h.gate.allows(CompletionEvent.SessionIdle("s", 1), ticket))
    }

    @Test fun `concurrent unresolved events share the metadata refresh`() = runTest {
        val h = Harness()
        val refreshing = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        h.refresh = { refreshing.complete(Unit); resume.await(); h.kind = NotificationSessionKind.ORDINARY }
        val a = async { h.gate.allows(CompletionEvent.SessionIdle("a", 1)) }
        refreshing.await()
        val b = async { h.gate.allows(CompletionEvent.SessionIdle("b", 1)) }
        resume.complete(Unit)
        assertTrue(a.await())
        assertTrue(b.await())
        assertEquals(1, h.refreshCount)
    }
}
