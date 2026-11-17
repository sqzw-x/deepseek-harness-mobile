package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatBlock
import com.labteto.dshmobile.core.session.CommandNode
import com.labteto.dshmobile.core.session.CompactionNode
import com.labteto.dshmobile.core.session.EventFold
import com.labteto.dshmobile.core.session.OtherNode
import com.labteto.dshmobile.core.session.PtcDispatchNode
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import com.labteto.dshmobile.core.session.TurnEndNode
import com.labteto.dshmobile.core.session.TurnStartNode
import com.labteto.dshmobile.core.session.UserMessageNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A node that draws nothing must not reach the transcript's list.
 *
 * The list spaces its rows with `Arrangement.spacedBy`, which happily puts 4dp between two
 * zero-height items — so a turn's worth of bookkeeping events stacks into a visible white band
 * above the conversation. These pin the predicate that keeps them out.
 */
class ChatNodeVisibilityTest {
    private fun streamingTool() = AssistantMessageNode(
        seq = 7, messageId = null, turn = 1, step = 1, streaming = true,
        blocks = listOf(ChatBlock(kind = "tool-call", toolCallId = "c", toolName = "read",
            argumentsJson = "{\"path\":\"src/ทดสอบ")),
    )

    @Test fun `tool-only streaming node reaches the transcript before execution`() {
        val node = streamingTool()
        assertEquals(listOf(node), renderableChatNodes(listOf(node), running = true))
        assertTrue(node.showsToolPreview(node.blocks.single(), true, emptySet()))
    }

    @Test fun `durable tool card replaces the inline preview even if streaming tail remains`() {
        val node = streamingTool()
        val call = ToolCallNode(seq = 6, callId = "c", name = "read", arguments = "{}", turn = 1, step = 1)
        assertEquals(listOf(call), renderableChatNodes(listOf(call, node), running = true))
        assertFalse(node.showsToolPreview(node.blocks.single(), true, setOf("c")))
    }

    @Test fun `non-streaming tool-only messages stay hidden during a running turn`() {
        val node = streamingTool().copy(streaming = false)
        assertTrue(renderableChatNodes(listOf(node), running = true).isEmpty())
        assertFalse(node.showsToolPreview(node.blocks.single(), true, emptySet()))
    }

    @Test fun `stopping the turn hides a provisional preview without changing nodes`() {
        val nodes = listOf(streamingTool())
        assertEquals(nodes, renderableChatNodes(nodes, running = true))
        assertTrue(renderableChatNodes(nodes, running = false).isEmpty())
    }

    @Test fun `another call does not hide this tools partial argument preview`() {
        val node = streamingTool()
        val other = ToolCallNode(seq = 6, callId = "other", name = "read", arguments = "{}", turn = 1, step = 1)
        assertEquals(listOf(other, node), renderableChatNodes(listOf(other, node), running = true))
        val preview = toolRowModel(node.blocks.single().toolName!!, node.blocks.single().argumentsJson, null)
        assertEquals("Read", preview.title)
        assertEquals("src/ทดสอบ", preview.summary)
    }

    @Test fun `interrupted messages keep the stopped marker but no live tool preview`() {
        val node = streamingTool().copy(interrupted = true)
        assertEquals(listOf(node), renderableChatNodes(listOf(node), running = true))
        assertFalse(node.showsToolPreview(node.blocks.single(), true, emptySet()))
    }

    @Test
    fun `structural nodes draw nothing`() {
        assertFalse(TurnStartNode(seq = 1, turn = 1).rendersContent())
        assertFalse(
            ToolResultNode(seq = 2, callId = "c", content = null, isError = false, turn = 1, step = 1)
                .rendersContent(),
        )
        assertFalse(TurnEndNode(seq = 3, turn = 1, reasonKind = "completed").rendersContent())
        // A dispatched call is drawn under its parent, never as a row of its own.
        assertFalse(
            PtcDispatchNode(
                seq = 4, rootCallId = "c", parentCallId = "c", subCallId = "c:1",
                name = "bash", argumentsJson = "{}", time = 4,
            ).rendersContent(),
        )
    }

    @Test
    fun `an unclean turn ending is worth a row`() {
        assertTrue(TurnEndNode(seq = 3, turn = 1, reasonKind = "interrupted").rendersContent())
        assertTrue(TurnEndNode(seq = 3, turn = 1, reasonKind = "error").rendersContent())
    }

    @Test
    fun `bookkeeping event types are filtered, unknown ones survive`() {
        // The compatibility contract keeps genuinely unknown types visible; the noise is named.
        assertFalse(OtherNode(seq = 4, type = "step/start", data = JsonNull).rendersContent())
        assertFalse(OtherNode(seq = 5, type = "assistant/chunk", data = JsonNull).rendersContent())
        // The turn's settled file changes draw the changed-files card, so this one is content.
        assertTrue(OtherNode(seq = 6, type = "workspace/changes", data = JsonNull).rendersContent())
        assertTrue(OtherNode(seq = 7, type = "something/new", data = JsonNull).rendersContent())
    }

    @Test
    fun `the job lifecycle never prints itself into the transcript`() {
        // Jobs are drawn from the job/list and job/follow streams; a lifecycle event is the same
        // fact again, as a wire name. The prefix rule covers phases this build has not met yet.
        assertFalse(OtherNode(seq = 1, type = "job/registered", data = JsonNull).rendersContent())
        assertFalse(OtherNode(seq = 2, type = "job/settled", data = JsonNull).rendersContent())
        assertFalse(OtherNode(seq = 3, type = "job/whatever-comes-next", data = JsonNull).rendersContent())
        assertTrue(isStructuralEventType("job/output"))
        assertFalse(isStructuralEventType("jobs/something"))
    }

    @Test
    fun `bookkeeping between two calls does not split the run`() {
        // The filter has to run before grouping: a row that draws nothing but is still in the list
        // is a boundary, and one `job/settled` would cut a run of work in half.
        val nodes = listOf(
            ToolCallNode(seq = 1, callId = "a", name = "read", arguments = "{}", turn = 1, step = 1),
            OtherNode(seq = 2, type = "job/settled", data = JsonNull),
            ToolCallNode(seq = 3, callId = "b", name = "read", arguments = "{}", turn = 1, step = 1),
        )
        val rows = groupToolRows(renderableChatNodes(nodes, running = true))
        assertEquals(1, rows.size)
        assertEquals(listOf(1L, 3L), (rows[0] as TranscriptRow.Activity).items.map { it.seq })
    }

    // Issue #96: known translator bookkeeping stays out, without making every ignorable record
    // disappear. Unknown events, including other plugins' records, keep their raw disclosure.
    @Test
    fun `known translator machine records are not transcript rows`() {
        val request = OtherNode(seq = 4, type = "plugin:translator/request", data = JsonNull, ignorable = true)
        val result = OtherNode(seq = 5, type = "plugin:translator/result", data = JsonNull, ignorable = true)
        assertFalse(request.rendersContent())
        assertFalse(result.rendersContent())

        val reply = AssistantMessageNode(
            seq = 6, messageId = "a", turn = 1, step = 1, blocks = listOf(ChatBlock("text", "done")),
        )
        assertEquals(listOf(reply), renderableChatNodes(listOf(request, result, reply), running = false))
    }

    @Test
    fun `an unknown event the harness did not mark skippable still renders`() {
        // Required-but-unrecognised events stay on screen: that is the compatibility contract.
        assertTrue(OtherNode(seq = 4, type = "something/new", data = JsonNull).rendersContent())
        assertTrue(OtherNode(seq = 5, type = "something/new", data = JsonNull, ignorable = false).rendersContent())
        // A known namespace does not make a newly added record type known machine noise.
        assertTrue(OtherNode(seq = 6, type = "plugin:translator/future", data = JsonNull).rendersContent())
    }

    @Test
    fun `a marker cannot hide a type this build draws`() {
        assertTrue(OtherNode(seq = 4, type = "workspace/changes", data = JsonNull, ignorable = true).rendersContent())
        assertTrue(OtherNode(seq = 5, type = "deliverables/presented", data = JsonNull, ignorable = true).rendersContent())
    }

    @Test
    fun `a skipped plugin record stays in the journal and in the folded nodes Trajectory reads`() {
        val events = listOf(
            SessionEventEnvelope("turn/start", 0, 0, buildJsonObject { put("turn", 1) }),
            SessionEventEnvelope("plugin:translator/request", 1, 1, buildJsonObject { put("provider", "deepseek-account") }, ignorable = true),
            SessionEventEnvelope("plugin:translator/result", 2, 2, buildJsonObject { put("requestSeq", 1) }, ignorable = true),
        )
        val snapshot = EventFold("s1").fold(events)

        // Chat draws nothing for them...
        assertTrue(renderableChatNodes(snapshot.nodes, running = false).isEmpty())
        // ...while Trajectory, which re-folds the journal and lists every event raw, still has both.
        val refolded = EventFold("s1").fold(snapshot.journal.map { it.copy(surfaceOp = null, surfaceIntent = null) })
        assertEquals(
            listOf("plugin:translator/request", "plugin:translator/result"),
            refolded.nodes.filterIsInstance<OtherNode>().map { it.type },
        )
        assertEquals(listOf("plugin:translator/request", "plugin:translator/result"), snapshot.journal.drop(1).map { it.type })
    }

    @Test
    fun `ignorable unknown events and plugins remain visible`() {
        val unknown = OtherNode(seq = 8, type = "future/new", data = JsonNull, ignorable = true)
        val plugin = OtherNode(seq = 9, type = "plugin:future/record", data = JsonNull, ignorable = true)
        val knownTranslator = OtherNode(seq = 10, type = "plugin:translator/result", data = JsonNull)
        assertEquals(listOf(unknown, plugin), renderableChatNodes(listOf(unknown, plugin, knownTranslator), false))
        assertTrue(unknown.rendersContent())
        assertTrue(plugin.rendersContent())
        // Known noise is hidden even if a legacy host omitted the marker.
        assertFalse(knownTranslator.rendersContent())
        val rows = groupToolRows(listOf(ToolCallNode(7, "a", "read", "{}", 1, 1), plugin, ToolCallNode(11, "b", "read", "{}", 1, 1)))
        assertEquals(listOf(7L, 9L, 11L), rows.map { it.seq })
    }

    @Test
    fun `a rewind removes both the answer and its synthetic thought`() {
        val mixed = AssistantMessageNode(
            seq = 3, messageId = "a3", turn = 1, step = 1,
            blocks = listOf(ChatBlock("reasoning", "private thinking"), ChatBlock("text", "answer")),
        )
        val run = CommandNode(4, "command/run", Json.parseToJsonElement("""{"commandId":"c","name":"rewind","args":"@2 chat"}"""))
        val done = CommandNode(5, "command/done", Json.parseToJsonElement("""{"commandId":"c","kind":"success","sourceEventSeq":6}"""))
        val marker = UserMessageNode(6, "marker", listOf(ChatBlock("text", "(empty message)")), "dsh-rewind")
        val nodes = listOf(TurnStartNode(1, 1), UserMessageNode(2, "m2", listOf(ChatBlock("text", "prompt")), "user"), mixed, run, done, marker)
        val visible = renderableChatNodes(splitReasoning(nodes), running = false)
        assertEquals(listOf(6L), visible.map { it.seq })
        assertTrue((visible.single() as UserMessageNode).isRewindMarker)
    }

    @Test
    fun `an assistant message of nothing but tool references draws nothing`() {
        val node = AssistantMessageNode(
            seq = 7,
            messageId = null,
            turn = 1,
            step = 1,
            blocks = listOf(
                ChatBlock(kind = "tool-call", toolCallId = "c", toolName = "bash"),
                ChatBlock(kind = "text", text = "   "),
            ),
        )
        assertFalse(node.rendersContent())
        assertTrue(node.copy(blocks = node.blocks + ChatBlock("text", "done")).rendersContent())
        assertTrue(node.copy(interrupted = true).rendersContent())
    }

    @Test
    fun `an empty user message draws nothing`() {
        val node = UserMessageNode(seq = 8, messageId = null, blocks = emptyList(), sourceKind = "user")
        assertFalse(node.rendersContent())
        assertTrue(node.copy(blocks = listOf(ChatBlock("text", "hello"))).rendersContent())
    }

    /**
     * A harness that labels a block something this client has not seen should still put the user's
     * words on screen. The predicate and the renderer share [displayText] so they cannot disagree
     * about it — a node judged renderable that then draws nothing costs a gap, and one judged empty
     * that holds text loses the message.
     */
    @Test
    fun `an unknown block carrying text still counts as a user message`() {
        val node = UserMessageNode(
            seq = 9,
            messageId = null,
            blocks = listOf(ChatBlock("unknown", text = "typed this")),
            sourceKind = "user",
        )
        assertTrue(node.rendersContent())
        assertEquals("typed this", node.displayText())
    }

    @Test
    fun `an unknown block with no text is still nothing to draw`() {
        val node = UserMessageNode(
            seq = 10,
            messageId = null,
            blocks = listOf(ChatBlock("unknown", text = null)),
            sourceKind = "user",
        )
        assertFalse(node.rendersContent())
        assertEquals("", node.displayText())
    }

    @Test
    fun `multiple text blocks join in order`() {
        val node = UserMessageNode(
            seq = 11,
            messageId = null,
            blocks = listOf(ChatBlock("text", "one"), ChatBlock("text", "two")),
            sourceKind = "user",
        )
        assertEquals("one\ntwo", node.displayText())
    }

    @Test
    fun `a turn exposes request context alongside tool calls`() {
        val nodes = listOf(
            TurnStartNode(seq = 1, turn = 1),
            OtherNode(seq = 2, type = "step/start", data = JsonNull),
            OtherNode(seq = 3, type = "request/header", data = JsonNull),
            OtherNode(seq = 4, type = "request/context", data = JsonNull),
            ToolCallNode(seq = 5, callId = "c1", name = "bash", arguments = "{}", turn = 1, step = 1),
            ToolResultNode(seq = 6, callId = "c1", content = null, isError = false, turn = 1, step = 1),
            OtherNode(seq = 7, type = "step/end", data = JsonNull),
            TurnEndNode(seq = 8, turn = 1, reasonKind = "completed"),
        )
        assertEquals(listOf(3L, 4L, 5L), nodes.filter { it.rendersContent() }.map { it.seq })
    }

    /** An empty reasoning block is spacing once settled, but the Thinking row while it streams. */
    @Test fun `blank reasoning renders only while its message is live`() {
        val live = AssistantMessageNode(seq = 9, messageId = null, turn = 1, step = 1, streaming = true,
            blocks = listOf(ChatBlock(kind = "reasoning", text = "")))
        assertTrue(live.rendersContent(running = true))
        assertFalse(live.rendersContent(running = false))
        assertFalse(live.copy(streaming = false).rendersContent(running = true))
        assertTrue(live.copy(streaming = false, blocks = listOf(ChatBlock("reasoning", "plan"))).rendersContent())
    }

    /** A rewind withdraws its range from the transcript; its marker stays, drawn as the divider. */
    @Test fun `a rewind cut hides what it withdrew and keeps its marker as the divider`() {
        fun user(seq: Long, kind: String = "user") = UserMessageNode(seq, "m$seq", listOf(ChatBlock("text", "text $seq")), kind)
        val run = CommandNode(40, "command/run", Json.parseToJsonElement("""{"commandId":"c","name":"rewind","args":"@20 chat"}"""))
        val done = CommandNode(42, "command/done", Json.parseToJsonElement("""{"commandId":"c","kind":"success","sourceEventSeq":41}"""))
        val marker = user(41, kind = "dsh-rewind")
        val rows = renderableChatNodes(listOf(user(10), user(20), user(30), run, marker, done, user(50)), running = false)
        assertEquals(listOf(10L, 41L, 50L), rows.map { it.seq })
        assertTrue((rows[1] as UserMessageNode).isRewindMarker)
    }

    // ---- compaction (issue #98) ------------------------------------------------------------

    private fun compaction(seq: Long, type: String, data: String) =
        SessionEventEnvelope(type, seq, seq, Json.parseToJsonElement(data) as JsonObject)

    private fun start(seq: Long, id: String) = compaction(seq, "compaction/start", """{"compactionId":"$id","turn":null}""")

    private fun summary(seq: Long, id: String, text: String? = "the summary of $id") = compaction(
        seq, "compaction/summary",
        """{"compactionId":"$id","summary":[${if (text == null) "" else """{"type":"text","text":"$text"}"""}],""" +
            """"shadowedRange":{"from":1,"to":9},"shadowedTokenCount":1200,"provider":"p","model":"m"}""",
    )

    private fun end(seq: Long, id: String, error: String? = null) =
        compaction(seq, "compaction/end", """{"compactionId":"$id"${if (error != null) ""","error":"$error"""" else ""}}""")

    private fun prune(seq: Long) = compaction(seq, "compaction/prune", """{"shadowedRange":{"from":3,"to":5},"shadowedTokenCount":300}""")

    private fun userEvent(seq: Long, text: String) = SessionEventEnvelope(
        "user/message", seq, seq,
        Json.parseToJsonElement("""{"id":"m$seq","content":[{"type":"text","text":"$text"}],"source":{"kind":"user"}}""") as JsonObject,
    )

    private fun toolEvent(seq: Long, callId: String) = SessionEventEnvelope(
        "tool/call", seq, seq,
        Json.parseToJsonElement("""{"turn":1,"step":1,"callId":"$callId","name":"read","arguments":"{}"}""") as JsonObject,
    )

    private fun drawnCompactions(nodes: List<com.labteto.dshmobile.core.session.ChatNode>) =
        renderableChatNodes(nodes, running = false).filterIsInstance<CompactionNode>()

    @Test fun `only a compaction summary draws a row`() {
        fun node(event: SessionEventEnvelope) = CompactionNode(event.seq, event.type, event.data)
        assertFalse(node(start(1, "c")).rendersContent())
        assertTrue(node(summary(2, "c")).rendersContent())
        assertFalse(node(end(3, "c")).rendersContent())
        // A failed attempt would otherwise draw a "Compacted" row that is not true.
        assertFalse(node(end(3, "c", error = "model unavailable")).rendersContent())
        assertFalse(node(prune(4)).rendersContent())
    }

    @Test fun `one compaction is one row however many events it wrote`() {
        val events = listOf(start(10, "c1"), summary(11, "c1"), end(12, "c1"))
        val snapshot = EventFold("s").fold(events)
        // The fold still produces every node; the presentation decides which one is drawn.
        assertEquals(3, snapshot.nodes.count { it is CompactionNode })
        assertEquals(listOf(11L), drawnCompactions(snapshot.nodes).map { it.seq })
    }

    @Test fun `a prune draws nothing and two compactions draw two rows`() {
        val events = listOf(
            start(10, "c1"), summary(11, "c1"), end(12, "c1"),
            prune(20),
            start(30, "c2"), summary(31, "c2"), end(32, "c2"),
        )
        val snapshot = EventFold("s").fold(events)
        assertEquals(listOf(11L, 31L), drawnCompactions(snapshot.nodes).map { it.seq })
    }

    @Test fun `a summary without text keeps its row`() {
        val snapshot = EventFold("s").fold(listOf(start(10, "c1"), summary(11, "c1", text = null), end(12, "c1")))
        assertEquals(listOf(11L), drawnCompactions(snapshot.nodes).map { it.seq })
    }

    @Test fun `messages and tool calls around a compaction draw as before`() {
        val events = listOf(
            userEvent(1, "hello"),
            toolEvent(2, "c"),
            start(3, "c1"), summary(4, "c1"), end(5, "c1"),
            prune(6),
            userEvent(7, "next"),
        )
        val withCompaction = renderableChatNodes(EventFold("s").fold(events).nodes, running = false)
        val without = renderableChatNodes(
            EventFold("s").fold(events.filterNot { it.type.startsWith("compaction/") }).nodes,
            running = false,
        )
        assertEquals(listOf(1L, 2L, 7L), without.map { it.seq })
        assertEquals(listOf(1L, 2L, 4L, 7L), withCompaction.map { it.seq })
        // The grouping layer sees the same rows, with the compaction as its own row in between.
        assertEquals(listOf(1L, 2L, 4L, 7L), groupToolRows(withCompaction).map { it.seq })
    }

    @Test fun `reopening the session and replaying its events does not draw a compaction twice`() {
        val events = listOf(userEvent(1, "hello"), start(3, "c1"), summary(4, "c1"), end(5, "c1"), prune(6))
        // History replay: a fresh fold of the same journal.
        assertEquals(listOf(4L), drawnCompactions(EventFold("s").fold(events).nodes).map { it.seq })
        // A reconnect that re-sends events the screen already holds is dropped by seq.
        val held = EventFold("s").fold(events)
        val incremental = EventFold.Incremental(held, "s")
        events.forEach { assertNull(incremental.apply(it)) }
        assertEquals(listOf(4L), drawnCompactions(held.nodes).map { it.seq })
    }
}
