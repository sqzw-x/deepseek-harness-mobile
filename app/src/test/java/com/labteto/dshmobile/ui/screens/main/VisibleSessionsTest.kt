package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.data.SessionRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a list surface may show: not archived, not blank, and not a subagent an archived ancestor
 * took out of the list with it.
 *
 * The harness archives exactly what was named, so a parent's children keep no archived marker of
 * their own. Without this they come back as top-level rows — landing in the drawer's "other" block,
 * because no workspace claims them.
 */
class VisibleSessionsTest {

    private fun row(
        id: String,
        parent: String? = null,
        origin: String? = null,
        blank: Boolean = false,
    ) = SessionRow(
        sessionId = id,
        title = id,
        running = false,
        blank = blank,
        parentSessionId = parent,
        origin = origin,
        cwd = null,
        agentPreset = null,
        updatedAt = 0L,
        pendingInteraction = null,
    )

    @Test fun `a subagent goes with its archived parent`() {
        val sessions = listOf(
            row("p"),
            row("c", parent = "p", origin = "subagent"),
            row("keep"),
        )
        assertEquals(listOf("p", "keep"), visibleSessions(sessions, setOf("p")).map { it.sessionId })
    }

    @Test fun `a subagent stays while its parent is on the list`() {
        val sessions = listOf(row("p"), row("c", parent = "p", origin = "subagent"))
        assertEquals(listOf("p", "c"), visibleSessions(sessions, emptySet()).map { it.sessionId })
    }

    @Test fun `a blank ancestor is stepped over, not treated as the parent`() {
        val sessions = listOf(
            row("grand"),
            row("middle", parent = "grand", blank = true),
            row("c", parent = "middle", origin = "subagent"),
        )
        assertEquals(emptyList<String>(), visibleSessions(sessions, setOf("grand")).map { it.sessionId })
        assertEquals(listOf("grand", "middle", "c"), visibleSessions(sessions, emptySet()).map { it.sessionId })
    }

    @Test fun `an archived row itself is not shown`() {
        assertEquals(emptyList<String>(), visibleSessions(listOf(row("p")), setOf("p")).map { it.sessionId })
    }

    @Test fun `a malformed lineage cycle terminates`() {
        val sessions = listOf(
            row("a", parent = "b", origin = "subagent"),
            row("b", parent = "a", origin = "subagent"),
        )
        visibleSessions(sessions, emptySet())
    }
}
