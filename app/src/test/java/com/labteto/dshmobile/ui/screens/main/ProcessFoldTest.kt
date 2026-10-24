package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.*
import org.junit.Assert.*
import org.junit.Test

/**
 * A turn's steps fold into one row, and the steps inside it are this fork's runs of work.
 *
 * [groupToolRows] is what decides a run: consecutive reasoning, tool calls and machine events are
 * one [TranscriptRow.Activity], so a turn that thinks, calls a tool and answers is a single step and
 * stays a row of its own. The fold pays off when a turn narrated its way through several runs, which
 * is where upstream's "Process · N steps" row comes from.
 */
class ProcessFoldTest {
    private fun call(seq: Long) = ToolCallNode(seq, "c$seq", "read", "{}", 1, 1)
    private fun user(seq: Long, text: String = "hello") =
        UserMessageNode(seq, "u$seq", listOf(ChatBlock("text", text)), "user")
    private fun answer(seq: Long, text: String = "done") =
        AssistantMessageNode(seq, "a$seq", 1, 1, listOf(ChatBlock("text", text)))
    private fun reasoning(seq: Long, text: String = "thinking it over") =
        AssistantMessageNode(seq, "a$seq", 1, 1, listOf(ChatBlock("reasoning", text)))

    /** Matches the transcript: turn starts never reach the row list, only the fold's boundary list. */
    private fun fold(nodes: List<ChatNode>, turnStarts: List<Long>, running: Boolean = false) =
        foldProcessRows(groupToolRows(nodes), turnStarts, running)

    @Test fun `the work between a prompt and its answer folds into one row`() {
        val rows = fold(
            listOf(user(2), reasoning(3), call(4), answer(5, "reading it"), reasoning(6), call(7), answer(8)),
            turnStarts = listOf(1),
        )
        assertEquals(3, rows.size)
        assertTrue(rows[0] is TranscriptRow.Message)
        val process = rows[1] as TranscriptRow.Process
        // Two runs, with the narration the model wrote between them: the fold is the whole stretch.
        assertEquals(listOf(3L, 5L, 6L), process.rows.map { it.seq })
        assertEquals(5, process.steps)
        assertEquals(8L, rows[2].seq)
    }

    @Test fun `the prompt stays outside the fold`() {
        val rows = fold(
            listOf(user(2), reasoning(3), call(4), answer(5, "reading it"), reasoning(6), call(7), answer(8)),
            turnStarts = listOf(1),
        )
        val first = rows[0] as TranscriptRow.Message
        assertEquals(2L, first.seq)
        assertTrue(first.node is UserMessageNode)
    }

    @Test fun `a turn that answered straight away is left alone`() {
        val rows = fold(listOf(user(2), answer(3)), turnStarts = listOf(1))
        assertEquals(2, rows.size)
        assertTrue(rows.none { it is TranscriptRow.Process })
    }

    @Test fun `a running turn keeps its steps on screen`() {
        val rows = fold(
            listOf(user(2), reasoning(3), call(4), answer(5, "reading it"), reasoning(6), call(7), answer(8)),
            turnStarts = listOf(1),
            running = true,
        )
        assertEquals(5, rows.size)
        assertTrue(rows.none { it is TranscriptRow.Process })
    }

    @Test fun `injected context rides inside the run it was injected for`() {
        val skill = UserMessageNode(3, "s", listOf(ChatBlock("text", "skill body")), "skill-invocation")
        val rows = fold(
            listOf(user(2), skill, reasoning(4), call(5), answer(6, "reading it"), reasoning(7), call(8), answer(9)),
            turnStarts = listOf(1),
        )
        // Context the harness injected belongs to the work that follows it, so the run carries it
        // rather than the transcript spending a row on it. See `groupToolRows`.
        assertEquals(3, rows.size)
        val process = rows[1] as TranscriptRow.Process
        assertEquals(listOf(3L, 6L, 7L), process.rows.map { it.seq })
        assertTrue((process.rows.first() as TranscriptRow.Activity).items.any { it.seq == 3L })
        assertEquals(9L, rows[2].seq)
    }

    @Test fun `a steering message stays on screen and splits the fold`() {
        // Steering lands inside the running turn: no turn start of its own.
        val rows = fold(
            listOf(
                user(2), reasoning(3), call(4), answer(5, "looking"),
                user(6, "use the other file"), reasoning(7), call(8), answer(9, "checking"),
                reasoning(10), call(11), answer(12),
            ),
            turnStarts = listOf(1),
        )
        assertEquals(listOf(2L, 3L, 6L, 7L, 12L), rows.map { it.seq })
        assertEquals(listOf(3L, 5L), (rows[1] as TranscriptRow.Process).rows.map { it.seq })
        assertTrue((rows[2] as TranscriptRow.Message).node is UserMessageNode)
        assertEquals(listOf(7L, 9L, 10L), (rows[3] as TranscriptRow.Process).rows.map { it.seq })
        // The history sheet lists the steer as something the reader sent; its jump needs the row.
        assertEquals(2, historyRowIndex(rows, 6L))
    }

    @Test fun `the step count sums every call and every stretch of thinking`() {
        val rows = fold(
            listOf(user(2), call(3), call(4), answer(5, "read both"), answer(6)),
            turnStarts = listOf(1),
        )
        assertEquals(3, (rows[1] as TranscriptRow.Process).steps)
    }

    @Test fun `narration on the way to the answer folds with the steps`() {
        val rows = fold(
            listOf(user(2), answer(3, "let me read that file"), call(4), answer(5)),
            turnStarts = listOf(1),
        )
        assertEquals(3, rows.size)
        assertEquals(listOf(3L, 4L), (rows[1] as TranscriptRow.Process).rows.map { it.seq })
        assertEquals(5L, rows[2].seq)
    }

    @Test fun `a single run stays a row of its own`() {
        val rows = fold(listOf(user(2), call(3), answer(4)), turnStarts = listOf(1))
        assertEquals(3, rows.size)
        assertTrue(rows[1] is TranscriptRow.Activity)
    }

    @Test fun `a turn whose reply never settled is left alone`() {
        val rows = fold(
            listOf(user(2), reasoning(3), call(4), answer(5, "")),
            turnStarts = listOf(1),
        )
        assertEquals(3, rows.size)
        assertTrue(rows.none { it is TranscriptRow.Process })
    }

    @Test fun `each turn folds on its own`() {
        val rows = fold(
            listOf(
                user(2), reasoning(3), call(4), answer(5, "reading"), reasoning(6), call(7), answer(8),
                user(10), reasoning(11), call(12), answer(13, "reading"), reasoning(14), call(15), answer(16),
            ),
            turnStarts = listOf(1, 9),
        )
        assertEquals(listOf(2L, 3L, 8L, 10L, 11L, 16L), rows.map { it.seq })
        assertEquals(listOf(3L, 5L, 6L), (rows[1] as TranscriptRow.Process).rows.map { it.seq })
        assertEquals(listOf(11L, 13L, 14L), (rows[4] as TranscriptRow.Process).rows.map { it.seq })
    }

    @Test fun `rows before the first turn start are untouched`() {
        val rows = fold(listOf(user(1), reasoning(2), answer(3)), turnStarts = listOf(9))
        assertEquals(3, rows.size)
        assertTrue(rows.none { it is TranscriptRow.Process })
    }

    @Test fun `no turn starts means nothing to fold against`() {
        val rows = fold(listOf(user(1), reasoning(2), call(3), answer(4)), turnStarts = emptyList())
        assertEquals(3, rows.size)
    }
}
