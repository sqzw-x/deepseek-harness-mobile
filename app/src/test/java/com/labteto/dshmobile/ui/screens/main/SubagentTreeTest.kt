package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.data.SessionRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the drawer decides what nests under what.
 *
 * The distinction that carries the whole feature is `origin`: `parentSessionId` alone does not mean
 * "subagent", because an ordinary fork sets one too — and a fork is a session in its own right that
 * belongs at the top level, not tucked inside the session it branched from.
 */
class SubagentTreeTest {

    private fun session(
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

    private fun index(listable: List<SessionRow>, all: List<SessionRow> = listable) =
        indexSubagents(listable, all.associateBy { it.sessionId })

    private fun listed(all: List<SessionRow>, archived: Set<String>) =
        visibleSessions(all, archived).map { it.sessionId }

    @Test
    fun `a subagent nests under its parent`() {
        val rows = listOf(session("root"), session("kid", parent = "root", origin = "subagent"))
        assertEquals(mapOf("root" to listOf(rows[1])), index(rows))
    }

    @Test
    fun `a fork stays at the top level`() {
        val rows = listOf(session("root"), session("forked", parent = "root"))
        assertTrue(index(rows).isEmpty())
    }

    @Test
    fun `a session with no parent nests nowhere`() {
        assertTrue(index(listOf(session("root"), session("other"))).isEmpty())
    }

    @Test
    fun `subagents of a subagent nest under it, not under the root`() {
        val rows = listOf(
            session("root"),
            session("kid", parent = "root", origin = "subagent"),
            session("grandkid", parent = "kid", origin = "subagent"),
        )
        val tree = index(rows)
        assertEquals(listOf("kid"), tree["root"]?.map { it.sessionId })
        assertEquals(listOf("grandkid"), tree["kid"]?.map { it.sessionId })
    }

    /** Archiving a parent used to take its subagents off the list with it. */
    @Test
    fun `a subagent whose parent is hidden re-attaches to the nearest visible ancestor`() {
        val root = session("root")
        val hidden = session("hidden", parent = "root", origin = "subagent")
        val orphan = session("orphan", parent = "hidden", origin = "subagent")
        val tree = index(listable = listOf(root, orphan), all = listOf(root, hidden, orphan))
        assertEquals(listOf("orphan"), tree["root"]?.map { it.sessionId })
    }

    @Test
    fun `a subagent with no visible ancestor is left ungrouped`() {
        val hidden = session("hidden", origin = "subagent")
        val orphan = session("orphan", parent = "hidden", origin = "subagent")
        assertTrue(index(listable = listOf(orphan), all = listOf(hidden, orphan)).isEmpty())
    }

    /** A malformed lineage must not spin the walk forever — the drawer would hang with it. */
    @Test
    fun `a lineage cycle terminates`() {
        val a = session("a", parent = "b", origin = "subagent")
        val b = session("b", parent = "a", origin = "subagent")
        val tree = index(listable = listOf(a), all = listOf(a, b))
        assertTrue(tree.isEmpty())
    }

    @Test
    fun `several subagents of one parent are grouped together`() {
        val rows = listOf(
            session("root"),
            session("k1", parent = "root", origin = "subagent"),
            session("k2", parent = "root", origin = "subagent"),
        )
        assertEquals(listOf("k1", "k2"), index(rows)["root"]?.map { it.sessionId })
    }

    // ------------------------------------------------------- what a list surface may show at all

    /**
     * The harness archives exactly what was named, so a parent's subagents keep their own
     * unarchived rows and the lineage walk had nothing to attach them to — each one was drawn at
     * the top level, and one archived agent team filled the drawer on its own.
     */
    @Test
    fun `archiving a parent buries its subagents`() {
        val all = listOf(
            session("root"),
            session("kid", parent = "root", origin = "subagent"),
            session("other"),
        )
        assertEquals(listOf("other"), listed(all, setOf("root")))
    }

    /**
     * Burying one generation is not enough. The grandchildren re-attach to the nearest ancestor
     * that looks alive, and when that ancestor is itself buried they land on the top level — the
     * same wall, one level down.
     */
    @Test
    fun `a subagent is buried by an archived ancestor, however deep`() {
        val all = listOf(
            session("root"),
            session("kid", parent = "root", origin = "subagent"),
            session("grandkid", parent = "kid", origin = "subagent"),
            session("other"),
        )
        assertEquals(listOf("other"), listed(all, setOf("root")))
    }

    /** A blank session is not an archive's doing: the subagent still has somewhere to re-attach. */
    @Test
    fun `a blank parent does not bury its subagent`() {
        val all = listOf(
            session("root"),
            session("scratch", parent = "root", origin = "subagent", blank = true),
            session("kid", parent = "scratch", origin = "subagent"),
        )
        assertEquals(listOf("root", "kid"), listed(all, emptySet()))
    }

    /** A fork is a session in its own right: archiving what it forked from does not take it. */
    @Test
    fun `a fork outlives the session it forked from`() {
        val all = listOf(session("root"), session("forked", parent = "root"))
        assertEquals(listOf("forked"), listed(all, setOf("root")))
    }

    /** A live ancestor is a way back to the subagent, however far up the chain it sits. */
    @Test
    fun `a subagent of a live fork survives the archived root`() {
        val all = listOf(
            session("root"),
            session("forked", parent = "root"),
            session("kid", parent = "forked", origin = "subagent"),
        )
        assertEquals(listOf("forked", "kid"), listed(all, setOf("root")))
    }

    /** A parent the registry no longer carries is the older orphan case, not an archive's doing. */
    @Test
    fun `an orphaned subagent is left where it was`() {
        val all = listOf(session("kid", parent = "gone", origin = "subagent"))
        assertEquals(listOf("kid"), listed(all, emptySet()))
    }

    /** A malformed lineage must terminate here too, not only in the nesting walk. */
    @Test
    fun `a lineage cycle does not bury`() {
        val a = session("a", parent = "b", origin = "subagent")
        val b = session("b", parent = "a", origin = "subagent")
        assertEquals(listOf("a", "b"), listed(listOf(a, b), emptySet()))
    }
}
