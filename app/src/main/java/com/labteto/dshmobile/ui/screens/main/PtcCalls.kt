package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.PtcDispatchNode
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import com.labteto.dshmobile.ui.components.DisclosureRow
import com.labteto.dshmobile.ui.components.DisclosureState
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.ToolCardBody
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.serialization.json.JsonElement

/**
 * The calls a `run_code` program dispatched, drawn under the call that ran it.
 *
 * PTC mode makes the program one tool call from the transcript's point of view, but everything it
 * did happened through the calls it dispatched, and a reader who cannot see them has lost the step
 * they came to read. The harness logs each one — `tool/ptc-dispatch-start` and its settlement —
 * and the fold turns the pair into one [PtcDispatchNode]; this is the tree those nodes form.
 *
 * The web client nests them the same way: the calls are the program's own column, indented behind a
 * hairline, each rendered as the tool call it is rather than as a raw event.
 */
@Composable
internal fun PtcSubCalls(parentCallId: String, context: ChatNodeContext) {
    val calls = remember(context.nodes, parentCallId) {
        ptcCalls(parentCallId, ptcCallsByParent(context.nodes))
    }
    PtcCallColumn(calls, context)
}

/** One sub-call carried with the calls it dispatched itself, so the tree is walked once. */
internal data class PtcCall(
    val callId: String,
    val name: String,
    val argumentsJson: String,
    val content: JsonElement?,
    val isError: Boolean,
    val settled: Boolean,
    val time: Long,
    val settledTime: Long?,
    val meta: JsonElement?,
    val children: List<PtcCall>,
)

/**
 * The dispatch edges by parent call, in log order.
 *
 * A parent that pointed at itself, or a cycle a malformed host minted, is refused by
 * [ptcCalls] rather than followed; the depth cap is the same safeguard against a tree deep enough
 * to cost the first frame a second measurement pass.
 */
internal fun ptcCallsByParent(nodes: List<ChatNode>): Map<String, List<PtcDispatchNode>> =
    nodes.filterIsInstance<PtcDispatchNode>()
        .distinctBy { it.subCallId }
        .groupBy { it.parentCallId }

/** [parentCallId]'s dispatched calls, each with its own, recursively. */
internal fun ptcCalls(
    parentCallId: String,
    byParent: Map<String, List<PtcDispatchNode>>,
    ancestors: Set<String> = emptySet(),
    depth: Int = 0,
): List<PtcCall> {
    if (parentCallId.isEmpty() || parentCallId in ancestors || depth >= MAX_PTC_DEPTH) return emptyList()
    val at = ancestors + parentCallId
    // An edge back up is refused rather than followed: a malformed host must not turn the tree into
    // a loop, and refusing the one edge keeps the rest of the program's calls readable.
    return byParent[parentCallId].orEmpty().filterNot { it.subCallId in at }.map { node ->
        PtcCall(
            callId = node.subCallId,
            name = node.name,
            argumentsJson = node.argumentsJson,
            content = node.content,
            isError = node.isError,
            settled = node.settled,
            time = node.time,
            settledTime = node.settledTime,
            meta = node.meta,
            children = ptcCalls(node.subCallId, byParent, at, depth + 1),
        )
    }
}

/** A host-minted edge deeper than this is refused; nothing a program does nests this far. */
private const val MAX_PTC_DEPTH = 12

/**
 * One program's dispatched calls as an indented column.
 *
 * The hairline is what makes the nesting legible on a phone: without it these rows look like
 * siblings of the call that ran the program rather than its own.
 */
@Composable
private fun PtcCallColumn(calls: List<PtcCall>, context: ChatNodeContext) {
    if (calls.isEmpty()) return
    val colors = DsTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = 4.dp.toPx()
                drawLine(
                    color = colors.borderL2,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(start = 12.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        calls.forEach { call -> key(call.callId) { PtcCallRow(call, context) } }
    }
}

/** One dispatched call: the same row a top-level call gets, at one level of nesting. */
@Composable
private fun PtcCallRow(call: PtcCall, context: ChatNodeContext) {
    val colors = DsTheme.colors
    val row = remember(call.name, call.argumentsJson, context.cwd) {
        toolRowModel(call.name, call.argumentsJson, context.cwd)
    }
    val running = !call.settled && context.running
    // The same derivation a top-level call gets, so a bash here is a terminal and a read is lines,
    // not a JSON blob: the sub-call's content and meta are the ones its own card reads.
    val card = remember(call, running, context.cwd, context.home) {
        buildToolCardView(
            call = ToolCallNode(
                seq = 0,
                callId = call.callId,
                name = call.name,
                arguments = call.argumentsJson,
                turn = 0,
                step = 0,
            ),
            result = call.content?.let { body ->
                ToolResultNode(
                    seq = 0,
                    callId = call.callId,
                    content = body,
                    isError = call.isError,
                    turn = 0,
                    step = 0,
                    meta = call.meta,
                )
            },
            running = running,
            cwd = context.cwd,
            home = context.home,
        )
    }
    var expanded by remember(call.callId) { mutableStateOf(false) }
    val state = when {
        call.isError -> DisclosureState.Error
        running -> DisclosureState.Running
        else -> DisclosureState.Idle
    }
    DisclosureRow(
        title = row.title,
        summary = row.summary,
        icon = if (call.name == "read_image") FeatherIcons.Image else row.variant.featherIcon(),
        state = state,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        Column(
            Modifier.padding(start = DsSpacing.medium, top = DsSpacing.tiny, bottom = DsSpacing.tiny),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        ) {
            ToolCardBody(card)
            ToolCallBody(card, call.content, call.meta, call.name)
        }
    }
    if (call.isError) {
        val failure = remember(call.content) { toolFailureMessage(call.content) }
        Text(
            failure ?: stringResource(R.string.common_error),
            style = DsType.caption11,
            color = colors.error,
            modifier = Modifier.padding(start = 26.dp),
        )
    }
    // A program can dispatch another `run_code`; what that one dispatched hangs under it here.
    PtcCallColumn(call.children, context)
}
