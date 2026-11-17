package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.PtcDispatchNode
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tree a `run_code` program's dispatched calls form.
 *
 * The fold gives one node per sub-call and the parent edge as a call id; the transcript walks those
 * edges to nest the rows. That walk — not the rendering — is what these pin: order, nesting, and
 * the two malformed cases a host could mint (a cycle and a tree nobody would want to draw).
 */
class PtcCallsTest {

    private fun dispatched(
        seq: Long,
        subCallId: String,
        parentCallId: String = ROOT,
        settled: Boolean = true,
    ) = PtcDispatchNode(
        seq = seq,
        rootCallId = ROOT,
        parentCallId = parentCallId,
        subCallId = subCallId,
        name = "bash",
        argumentsJson = "{\"command\":\"ls\"}",
        time = seq,
        content = if (settled) JsonPrimitive("ok") else null,
        settled = settled,
        settledTime = if (settled) seq + 1 else null,
    )

    private fun tree(nodes: List<PtcDispatchNode>, parent: String = ROOT) =
        ptcCalls(parent, ptcCallsByParent(nodes))

    @Test
    fun childrenAreTheParentsOwnInLogOrder() {
        val calls = tree(
            listOf(
                dispatched(1, "c:1"),
                dispatched(2, "c:2"),
                dispatched(3, "other:1", parentCallId = "other"),
            ),
        )
        assertEquals(listOf("c:1", "c:2"), calls.map { it.callId })
        assertTrue(calls.all { it.children.isEmpty() })
    }

    @Test
    fun aProgramThatDispatchedAnotherRunCodeKeepsTheNesting() {
        val calls = tree(
            listOf(
                dispatched(1, "c:1"),
                dispatched(2, "c:1:1", parentCallId = "c:1"),
                dispatched(3, "c:1:1:1", parentCallId = "c:1:1"),
                dispatched(4, "c:2"),
            ),
        )
        assertEquals(listOf("c:1", "c:2"), calls.map { it.callId })
        assertEquals(listOf("c:1:1"), calls[0].children.map { it.callId })
        assertEquals(listOf("c:1:1:1"), calls[0].children[0].children.map { it.callId })
        assertTrue(calls[0].children[0].children[0].children.isEmpty())
    }

    @Test
    fun aCycleIsRefusedRatherThanFollowed() {
        // A malformed edge that points back up: the walk stops instead of recursing forever.
        val calls = tree(
            listOf(
                dispatched(1, "a", parentCallId = "b"),
                dispatched(2, "b", parentCallId = "a"),
            ),
            parent = "a",
        )
        assertEquals(listOf("b"), calls.map { it.callId })
        assertTrue(calls.single().children.isEmpty())
    }

    @Test
    fun theSameSubCallTwiceInTheWindowIsOneRow() {
        // A window can overlap the same settlement; the row must not be drawn twice.
        val calls = tree(listOf(dispatched(1, "c:1"), dispatched(1, "c:1")))
        assertEquals(1, calls.size)
    }

    @Test
    fun anUnsettledCallStaysUnsettledSoTheRowCanLookRunning() {
        val call = tree(listOf(dispatched(1, "c:1", settled = false))).single()
        org.junit.Assert.assertFalse(call.settled)
        org.junit.Assert.assertNull(call.settledTime)
    }

    private companion object {
        const val ROOT = "call_root"
    }
}
