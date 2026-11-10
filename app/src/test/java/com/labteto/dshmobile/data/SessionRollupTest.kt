package com.labteto.dshmobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A parent reads as running while a subagent of its own is: the host puts the activity on the
 * child's row, and the list has to roll it up or a session waiting on its agents looks idle.
 */
class SessionRollupTest {

    private fun row(
        id: String,
        parent: String? = null,
        running: Boolean = false,
        origin: String? = null,
    ) = SessionRow(id, id, running, false, parent, origin, null, null, 0L, null)

    private fun rollup(vararg rows: SessionRow) = ancestorsOfRunning(rows.associateBy { it.sessionId })

    @Test fun `a running child marks its parent and nothing else`() {
        assertEquals(setOf("p"), rollup(row("p"), row("c", parent = "p", running = true, origin = "subagent"), row("x")))
    }

    @Test fun `every ancestor on the chain is marked, the root included`() {
        val rows = arrayOf(
            row("a"),
            row("b", parent = "a", origin = "subagent"),
            row("c", parent = "b", origin = "subagent", running = true),
        )
        assertEquals(setOf("a", "b"), rollup(*rows))
    }

    @Test fun `an idle tree marks nothing`() {
        assertTrue(rollup(row("a"), row("b", parent = "a", origin = "subagent")).isEmpty())
    }

    @Test fun `a malformed lineage cycle terminates`() {
        val ancestors = rollup(row("x", parent = "y", running = true), row("y", parent = "x"))
        assertTrue("y" in ancestors)
        assertTrue(ancestors.size <= 2)
    }
}
