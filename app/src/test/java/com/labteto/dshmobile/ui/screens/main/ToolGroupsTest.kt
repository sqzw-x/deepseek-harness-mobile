package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.*
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.*
import org.junit.Test

class ToolGroupsTest {
    private fun call(seq: Long) = ToolCallNode(seq, "c$seq", "read", "{}", 1, 1)
    private fun user(seq: Long) = UserMessageNode(seq, "u$seq", listOf(ChatBlock("text", "hello")), "user")
    private fun assistant(seq: Long) = AssistantMessageNode(seq, "a$seq", 1, 1, listOf(ChatBlock("text", "done")))
    private fun thinking(seq: Long, text: String = "weighing options") =
        AssistantMessageNode(seq, "t$seq", 1, 1, listOf(ChatBlock("reasoning", text)))

    private fun activity(row: TranscriptRow) = (row as TranscriptRow.Activity).items.map { it.seq }

    @Test fun `adjacent calls group without crossing visible message boundaries`() {
        val rows = groupToolRows(listOf(user(1), call(2), call(3), assistant(4), call(5), user(6)))
        assertEquals(5, rows.size)
        assertEquals(listOf(2L, 3L), activity(rows[1]))
        assertEquals(listOf(5L), activity(rows[3]))
    }

    @Test fun `reasoning joins the run it belongs to`() {
        val rows = groupToolRows(listOf(user(1), thinking(2), call(3), thinking(4), call(5), assistant(6)))
        assertEquals(3, rows.size)
        assertEquals(listOf(2L, 3L, 4L, 5L), activity(rows[1]))
        assertTrue(rows[2] is TranscriptRow.Message)
    }

    @Test fun `a message carrying text ends the run`() {
        val rows = groupToolRows(listOf(call(1), assistant(2), call(3)))
        assertEquals(3, rows.size)
        assertEquals(listOf(1L), activity(rows[0]))
        assertTrue(rows[1] is TranscriptRow.Message)
        assertEquals(listOf(3L), activity(rows[2]))
    }

    @Test fun `an assistant message with text and reasoning is the answer, not a step`() {
        val both = AssistantMessageNode(1, "a", 1, 1, listOf(ChatBlock("reasoning", "hmm"), ChatBlock("text", "here")))
        val rows = groupToolRows(listOf(both, call(2)))
        assertTrue(rows[0] is TranscriptRow.Message)
        assertEquals(listOf(2L), activity(rows[1]))
    }

    @Test fun `a streaming reasoning-only message is work too`() {
        val streaming = AssistantMessageNode(1, "a", 1, 1, listOf(ChatBlock("reasoning", "partial")), streaming = true)
        val rows = groupToolRows(listOf(streaming, call(2)))
        assertEquals(1, rows.size)
        assertEquals(listOf(1L, 2L), activity(rows[0]))
    }

    @Test fun `an attachment is never folded away`() {
        val picture = AssistantMessageNode(1, "a", 1, 1, listOf(ChatBlock("image")))
        val file = AssistantMessageNode(2, "b", 1, 1, listOf(ChatBlock("file")))
        val rows = groupToolRows(listOf(call(3), picture, call(4), file))
        assertEquals(4, rows.size)
        assertTrue(rows[1] is TranscriptRow.Message)
        assertTrue(rows[3] is TranscriptRow.Message)
    }

    @Test fun `a message with no work in it is not a run`() {
        val bare = AssistantMessageNode(1, "a", 1, 1, emptyList())
        val rows = groupToolRows(listOf(bare, call(2)))
        assertTrue(rows[0] is TranscriptRow.Message)
    }

    @Test fun `injected context joins the run it was injected for`() {
        val skill = UserMessageNode(2, "s", listOf(ChatBlock("text", "skill body")), "skill-invocation")
        val system = OtherNode(3, "system/message", JsonNull)
        val rows = groupToolRows(listOf(user(1), skill, system, call(4)))
        assertEquals(2, rows.size)
        assertTrue(rows[0] is TranscriptRow.Message)
        assertEquals(listOf(2L, 3L, 4L), activity(rows[1]))
    }

    @Test fun `context injected between two steps does not split the run`() {
        val notice = UserMessageNode(3, "n", listOf(ChatBlock("text", "job finished")), "tool-jobs")
        val rows = groupToolRows(listOf(call(1), thinking(2), notice, thinking(4), call(5)))
        assertEquals(1, rows.size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), activity(rows[0]))
    }

    @Test fun `a retry rides inside the run it interrupted`() {
        val retry = RetryNode(3, "llm/retry", JsonNull)
        val rows = groupToolRows(listOf(call(1), thinking(2), retry, thinking(4)))
        assertEquals(1, rows.size)
        assertEquals(listOf(1L, 2L, 3L, 4L), activity(rows[0]))
    }

    @Test fun `a retry with no run to ride in stays its own row`() {
        val retry = RetryNode(2, "llm/retry", JsonNull)
        val rows = groupToolRows(listOf(assistant(1), retry, call(3)))
        assertEquals(3, rows.size)
        assertTrue(rows[1] is TranscriptRow.Message)
    }

    @Test fun `an offloaded image note rides inside the run`() {
        val offload = OtherNode(2, "image/offload", JsonNull)
        val rows = groupToolRows(listOf(call(1), offload, call(3)))
        assertEquals(1, rows.size)
        assertEquals(listOf(1L, 2L, 3L), activity(rows[0]))
    }

    @Test fun `context after the answer keeps a row of its own`() {
        val notice = UserMessageNode(3, "n", listOf(ChatBlock("text", "time")), "time-context")
        val rows = groupToolRows(listOf(call(1), assistant(2), notice))
        assertEquals(3, rows.size)
        assertEquals(listOf(1L), activity(rows[0]))
        assertTrue(rows[1] is TranscriptRow.Message)
        assertEquals(listOf(3L), (rows[2] as TranscriptRow.Context).nodes.map { it.seq })
    }

    @Test fun `the rewind marker stays a message`() {
        val marker = UserMessageNode(2, "r", emptyList(), REWIND_SOURCE_KIND)
        val rows = groupToolRows(listOf(call(1), marker, call(3)))
        assertEquals(3, rows.size)
        assertEquals(listOf(1L), activity(rows[0]))
        assertTrue(rows[1] is TranscriptRow.Message)
        assertEquals(listOf(3L), activity(rows[2]))
    }

    @Test fun `group key remains the first step as the run grows`() {
        assertEquals(groupToolRows(listOf(call(1))).first().seq, groupToolRows(listOf(call(1), call(2))).first().seq)
        assertEquals(groupToolRows(listOf(thinking(1))).first().seq, groupToolRows(listOf(thinking(1), call(2))).first().seq)
        assertTrue(groupToolRows(emptyList()).isEmpty())
    }
}
