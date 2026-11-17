package com.labteto.dshmobile.ui.screens.main

import android.graphics.Bitmap
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.PtcDispatchNode
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * A PTC `run_code` call shows the calls its program dispatched, under the call that ran it.
 *
 * The transcript used to draw one "Code" row and nothing else: the harness logs every inner call
 * as `tool/ptc-dispatch-start` / `tool/ptc-dispatch`, but the fold dropped them, so the work a
 * program did was invisible. These pin the nesting the web client has — one row per dispatched
 * call, indented, readable like any other tool call — and capture the rendering for review.
 */
class PtcSubCallRenderTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun string(id: Int) = context.getString(id)
    private fun string(id: Int, arg: Int) = context.getString(id, arg)

    private val runCode = ToolCallNode(
        seq = 1,
        callId = "call_root",
        name = "run_code",
        arguments = """{"description":"Survey the repo","code":"await tools.bash({description:\"List the repo root\"})"}""",
        turn = 1,
        step = 1,
    )

    private fun dispatched(
        seq: Long,
        subCallId: String,
        name: String,
        description: String,
        result: String,
        settled: Boolean = true,
        parent: String = "call_root",
        isError: Boolean = false,
    ) = PtcDispatchNode(
        seq = seq,
        rootCallId = "call_root",
        parentCallId = parent,
        subCallId = subCallId,
        name = name,
        // Each variant reads its own argument for the row's subject: a read names a file, the rest
        // name themselves by their description.
        argumentsJson = when (name) {
            "read" -> """{"file_path":"$description"}"""
            else -> """{"description":"$description","command":"$description"}"""
        },
        time = seq * 1000,
        content = if (settled) Json.parseToJsonElement("""[{"type":"text","text":"$result"}]""") else null,
        isError = isError,
        settled = settled,
        settledTime = if (settled) seq * 1000 + 400 else null,
    )

    private val nodes: List<ChatNode> = listOf(
        runCode,
        dispatched(2, "call_root:ptc:1", "bash", "List the repo root", "app · core · docs"),
        dispatched(3, "call_root:ptc:2", "read", "build.gradle.kts", "plugins { }"),
        dispatched(4, "call_root:ptc:3", "grep", "Find the offsets", "match"),
        dispatched(
            5, "call_root:ptc:2:ptc:1", "bash", "Nested inside the read", "nested",
            parent = "call_root:ptc:2",
        ),
        ToolResultNode(
            seq = 6,
            callId = "call_root",
            content = Json.parseToJsonElement("""[{"type":"text","text":"logs: app core docs"}]"""),
            isError = false,
            turn = 1,
            step = 1,
        ),
    )

    private fun transcript(items: List<ChatNode> = nodes) {
        compose.setContent {
            DshTheme {
                ChatTranscript(
                    conversation = ConversationSnapshot(sessionId = "s1", nodes = items),
                    loading = false,
                    loadingOlder = false,
                    loadOlderFailed = false,
                    context = ChatNodeContext(
                        nodes = items,
                        running = false,
                        cwd = "/home/me/project",
                        onOpenSubagent = {},
                        onBranchFrom = {},
                        onFeedback = { _, _ -> },
                    ),
                    listState = rememberLazyListState(),
                    onLoadOlder = {},
                )
            }
        }
    }

    private fun capture(name: String) {
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun openRun() {
        compose.onNodeWithText(string(R.string.chat_tool_group_count, 1)).performClick()
        compose.waitForIdle()
    }

    @Test
    fun dispatchedCallsAreRowsUnderTheProgramThatRanThem() {
        transcript()
        openRun()

        compose.onNodeWithText("Survey the repo", substring = true).assertExists()
        // One row per dispatched call, each with its own verb and subject.
        compose.onNodeWithText("List the repo root", substring = true).assertExists()
        compose.onNodeWithText("build.gradle.kts", substring = true).assertExists()
        // The nested call hangs under the call that dispatched it, not under the program.
        compose.onNodeWithText("Nested inside the read", substring = true).assertExists()
        capture("ptc-subcalls-collapsed")

        // A sub-call opens into its own body: the call's arguments and what it returned.
        compose.onNodeWithText("List the repo root", substring = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("core", substring = true).assertExists()
        capture("ptc-subcalls-expanded")
    }

    @Test
    fun aSubCallIsDrawnOnceAndOnlyUnderItsParent() {
        transcript()
        openRun()

        // "Bash" appears as the sub-calls' verb and nowhere else: not as a top-level row of the run.
        compose.onAllNodesWithText("Bash").assertCountEquals(2)
    }

    @Test
    fun aFailedSubCallSaysSo() {
        transcript(
            listOf(
                runCode,
                dispatched(2, "call_root:ptc:1", "bash", "Break the build", "exit 1", isError = true),
                ToolResultNode(seq = 3, callId = "call_root", content = JsonPrimitive(""), isError = false, turn = 1, step = 1),
            ),
        )
        openRun()
        compose.onNodeWithText("exit 1", substring = true).assertExists()
    }
}
