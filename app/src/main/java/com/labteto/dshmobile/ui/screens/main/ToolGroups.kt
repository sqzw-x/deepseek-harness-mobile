package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.*
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.formatDurationMs
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

internal sealed interface TranscriptRow {
    val seq: Long
    data class Message(val node: ChatNode) : TranscriptRow { override val seq get() = node.seq }

    /**
     * A run of consecutive work: tool calls, the reasoning the model wrote between them, and the
     * context the harness injected for that work.
     *
     * The model's own answer is the boundary — a message carrying text is what the reader came for,
     * and it stays on screen while everything it took to produce it folds into one row above it.
     */
    data class Activity(val items: List<ChatNode>) : TranscriptRow { override val seq get() = items.first().seq }

    data class Context(val nodes: List<ChatNode>) : TranscriptRow { override val seq get() = nodes.first().seq }
}

/**
 * Whether a node is part of a run of work rather than something the reader came to read.
 *
 * A tool call is work by definition. An assistant message is work when it holds nothing but that
 * work — reasoning, or the arguments of a call still being written — and says nothing of its own:
 * no text, and nothing to look at either, because an image or a file chip folded into a collapsed
 * row is content the reader would have to go looking for.
 */
internal fun isActivityNode(node: ChatNode): Boolean = when (node) {
    is ToolCallNode -> true
    is AssistantMessageNode -> node.blocks.none { it.isAnswerContent() } && node.blocks.any { it.isWork() }
    else -> false
}

private fun ChatBlock.isAnswerContent(): Boolean = when (kind) {
    "text" -> !text.isNullOrBlank()
    "image", "file" -> true
    else -> false
}

private fun ChatBlock.isWork(): Boolean = kind == "reasoning" || kind == "tool-call"

/**
 * Whether a node is context the harness injected rather than something a person said.
 *
 * Injected context belongs to the work it was injected for, so the grouping buffers it and lets the
 * run that follows carry it — see [groupToolRows].
 */
internal fun isInjectedContextNode(node: ChatNode): Boolean = when (node) {
    // Context the reader did not type. A rewind marker is the one user row that is not a turn: it
    // divides the transcript where the conversation was cut back to, and it stays a row of its own.
    is UserMessageNode -> node.isInjectedContext && !node.isRewindMarker
    // A change to the model's tool set is context too; the app already labels it as such.
    is DeveloperMessageNode -> true
    is OtherNode -> node.type in CONTEXT_EVENT_TYPES
    else -> false
}

/**
 * Machinery that rides inside a run that is already open.
 *
 * A retry, an image the host dropped from context, a note that a teammate's message was delivered:
 * none of it is a step the reader asked to read, and each one used to end the run it landed in. A
 * turn that hit two such notices came out as three collapsed blocks — the last of them often no
 * more than a single thought, which is what made this visible. With no run open there is nothing to
 * ride in, so the node stays an ordinary row.
 */
internal fun isRunAttachment(node: ChatNode): Boolean = when (node) {
    is RetryNode -> true
    is OtherNode -> node.type in ATTACHMENT_EVENT_TYPES
    else -> false
}

/** Passthrough types that are context rather than work (see `EventClassification.PASSTHROUGH`). */
private val CONTEXT_EVENT_TYPES = setOf("system/message", "request/context")

/** Passthrough types that describe the machinery and never carry the reader's own content. */
private val ATTACHMENT_EVENT_TYPES = setOf(
    "image/offload",
    "team/member",
    "team/task",
    "team/message/queued",
    "team/message/delivered",
)

/**
 * Group adjacent work into one row, and anything the harness injected for that work into the same
 * row; context with no work to belong to gets a row of its own. Order is preserved: a row's `seq`
 * is its first member's.
 */
internal fun groupToolRows(nodes: List<ChatNode>): List<TranscriptRow> {
    val rows = mutableListOf<TranscriptRow>()
    val activity = mutableListOf<ChatNode>()
    fun flush() {
        if (activity.isNotEmpty()) {
            rows.add(TranscriptRow.Activity(activity.toList()))
            activity.clear()
        }
    }
    // Context waiting for the work it was injected for. A turn opens with a few of these — the time,
    // the workspace instructions, the skill catalog — and they belong inside the run that follows
    // rather than above it as a row of their own: the reader came for the turn's result.
    val contexts = mutableListOf<ChatNode>()
    fun flushContext() {
        if (contexts.isNotEmpty()) {
            rows.add(TranscriptRow.Context(contexts.toList()))
            contexts.clear()
        }
    }
    nodes.forEach {
        when {
            isActivityNode(it) -> {
                activity.addAll(contexts)
                contexts.clear()
                activity.add(it)
            }
            isInjectedContextNode(it) -> if (activity.isNotEmpty()) activity.add(it) else contexts.add(it)
            isRunAttachment(it) -> if (activity.isNotEmpty()) {
                activity.add(it)
            } else {
                flushContext()
                rows.add(TranscriptRow.Message(it))
            }
            else -> {
                flush()
                flushContext()
                rows.add(TranscriptRow.Message(it))
            }
        }
    }
    flush()
    flushContext()
    return rows
}

/**
 * One run of work, as a single line until the reader asks for more.
 *
 * Collapsed is the default and stays the default: the model can think and call tools for minutes,
 * and a transcript that prints all of it buries the answer that eventually arrives. Opening it
 * shows the individual steps, each still folded — the run is a table of contents, not a firehose.
 */
@Composable
internal fun ActivityGroupRow(items: List<ChatNode>, context: ChatNodeContext) {
    var expanded by remember(items.first().seq) { mutableStateOf(false) }
    val colors = DsTheme.colors
    val results = context.nodes.filterIsInstance<ToolResultNode>().associateBy { it.callId }
    val calls = items.filterIsInstance<ToolCallNode>()
    val thoughts = items.filterIsInstance<AssistantMessageNode>().sumOf { node ->
        node.blocks.count { it.kind == "reasoning" && !it.text.isNullOrBlank() }
    }
    val failed = calls.count { results[it.callId]?.isError == true }
    // The run is live while the last step has not settled: a call with no result, or reasoning that
    // is still arriving. Only the tail can be unfinished — an earlier step that never settled would
    // have ended the turn.
    val runningCall = (items.last() as? ToolCallNode)?.takeIf { it.callId !in results }
    val running = context.running && (runningCall != null || items.last() is AssistantMessageNode)
    val starts = items.mapNotNull { context.eventTimes[it.seq] }
    val ends = calls.mapNotNull { results[it.callId]?.let { r -> context.eventTimes[r.seq] } }
    val elapsed = if (starts.isNotEmpty() && ends.isNotEmpty()) (ends.max() - starts.min()).coerceAtLeast(0) else null

    Column(
        Modifier.fillMaxWidth().clip(DsShapes.block).background(colors.bgLayer1)
            .border(1.dp, colors.borderL2, DsShapes.block),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 36.dp)
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(FeatherIcons.Tool, null, Modifier.size(14.dp), tint = colors.labelTertiary)
            if (thoughts > 0) {
                Text(
                    stringResource(R.string.chat_activity_thoughts, thoughts),
                    style = DsType.small13,
                    color = colors.labelSecondary,
                )
            }
            if (thoughts > 0 && calls.isNotEmpty()) {
                Text("·", style = DsType.small13, color = colors.labelCaption)
            }
            if (calls.isNotEmpty()) {
                Text(
                    stringResource(R.string.chat_tool_group_count, calls.size),
                    style = DsType.small13,
                    color = colors.labelSecondary,
                )
            }
            if (failed > 0) {
                Text(
                    stringResource(R.string.chat_tool_group_failed, failed),
                    style = DsType.caption11,
                    color = colors.error,
                )
            }
            Spacer(Modifier.weight(1f))
            if (running) {
                // The hint that the run is alive. The browser's version narrates the step; this one
                // names the phase, which is the part that answers "is it stuck?".
                Text(
                    stringResource(
                        if (runningCall != null) R.string.chat_activity_running_tool else R.string.chat_thinking,
                    ),
                    style = DsType.caption11,
                    color = colors.accent,
                )
                WorkingDots(color = colors.accent)
            } else {
                Text(
                    elapsed?.let(::formatDurationMs).orEmpty(),
                    style = DsType.caption11,
                    color = colors.labelCaption,
                )
            }
            Icon(
                FeatherIcons.ChevronRight,
                null,
                Modifier.size(14.dp).graphicsLayer { rotationZ = if (expanded) 90f else 0f },
                tint = colors.labelTertiary,
            )
        }
        if (expanded) {
            // Bounded and scrollable inside, as the browser's panel is: opening a long run should not
            // push the answer it belongs to off the screen.
            Column(
                Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                items.forEach { item ->
                    key(item.seq) {
                        when (item) {
                            is ToolCallNode -> ToolCallRow(item, context, compact = true)
                            else -> ChatNodeItem(node = item, context = context)
                        }
                    }
                }
            }
        }
    }
}

/** Three dots that fill in one after another, so a still frame still reads as "alive". */
@Composable
private fun WorkingDots(color: androidx.compose.ui.graphics.Color) {
    val transition = rememberInfiniteTransition(label = "working")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1200), RepeatMode.Restart),
        label = "workingDots",
    )
    val dots = ".".repeat(phase.toInt().coerceIn(1, 3))
    Text(dots, style = DsType.caption11, color = color, modifier = Modifier.width(14.dp))
}

@Composable
internal fun ContextGroupRow(nodes: List<ChatNode>, context: ChatNodeContext) {
    var expanded by remember(nodes.first().seq) { mutableStateOf(false) }
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth().clip(DsShapes.block).background(colors.bgLayer1)
        .border(1.dp, colors.borderL2, DsShapes.block)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable(role = Role.Button) { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(FeatherIcons.Layers, null, Modifier.size(14.dp), tint = colors.labelCaption)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.chat_context_title) + " · " + nodes.size, style = DsType.small13, color = colors.labelTertiary)
            Spacer(Modifier.weight(1f))
            Icon(FeatherIcons.ChevronRight, null, Modifier.size(14.dp).graphicsLayer { rotationZ = if (expanded) 90f else 0f }, tint = colors.labelCaption)
        }
        if (expanded) Column(Modifier.padding(8.dp)) { nodes.forEach { key(it.seq) { ChatNodeItem(it, context) } } }
    }
}
