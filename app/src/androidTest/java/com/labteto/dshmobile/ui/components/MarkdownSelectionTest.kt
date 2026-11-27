package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A reply is one selection, not one per paragraph or per bullet.
 *
 * A `SelectionContainer` is a selection *domain*: a selection reaches only the texts registered with
 * the container it was started in. The renderer used to give every block its own — which is why a
 * hold in the first bullet could not be extended into the third, and why two paragraphs were two
 * selections — and a line with a link had none at all, so it could not be selected. These pin both
 * halves of the fix, through the one thing a selection can be observed by from a test: the platform
 * toolbar's Select all and Copy.
 *
 * The toolbar and the clipboard are recording fakes. The real ones belong to the device (system
 * clipboard, floating window), and neither can be read back from an instrumented test.
 */
class MarkdownSelectionTest {
    @get:Rule val compose = createComposeRule()

    private class RecordingToolbar : TextToolbar {
        var shown = false
        var selectAll: (() -> Unit)? = null
        var copy: (() -> Unit)? = null
        override val status: TextToolbarStatus
            get() = if (shown) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

        override fun hide() { shown = false }

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) {
            shown = true
            copy = onCopyRequested
            selectAll = onSelectAllRequested
        }
    }

    private class RecordingClipboard : ClipboardManager {
        var written: String? = null
        override fun setText(annotatedString: AnnotatedString) { written = annotatedString.text }
        override fun getText(): AnnotatedString? = written?.let(::AnnotatedString)
    }

    private val toolbar = RecordingToolbar()
    private val clipboard = RecordingClipboard()

    private val first = "Alpha item of the list."
    private val second = "Bravo item of the list."
    private val third = "Charlie item of the list."

    private fun render(markdown: String) {
        compose.setContent {
            DshTheme {
                CompositionLocalProvider(
                    LocalTextToolbar provides toolbar,
                    LocalClipboardManager provides clipboard,
                ) {
                    MarkdownText(markdown, Modifier.width(320.dp))
                }
            }
        }
        compose.waitForIdle()
    }

    private fun hold(textStart: String) {
        compose.onNodeWithText(textStart, substring = true, useUnmergedTree = true)
            .performTouchInput { longClick() }
        compose.waitForIdle()
    }

    @Test
    fun selectAllCopiesEveryItemOfOneList() {
        render("- $first\n- $second\n- $third")

        hold("Alpha item")

        assertTrue("A hold on the first item should raise Copy / Select all", toolbar.shown)
        val selectAll = toolbar.selectAll
        assertTrue("Select all should be offered while a word is selected", selectAll != null)
        compose.runOnUiThread { selectAll!!.invoke() }
        compose.waitForIdle()

        val copy = toolbar.copy
        assertTrue("A live selection should offer Copy", copy != null)
        compose.runOnUiThread { copy!!.invoke() }
        compose.waitForIdle()

        val copied = clipboard.written.orEmpty()
        assertTrue("One list is one selection, so Copy should carry the first item: \"$copied\"", copied.contains("Alpha item"))
        assertTrue("…and the third, which the old per-item containers could not reach: \"$copied\"", copied.contains("Charlie item"))
    }

    @Test
    fun selectAllSpansTwoParagraphs() {
        render("$first\n\n$second")

        hold("Alpha item")

        val selectAll = toolbar.selectAll
        assertTrue("Select all should be offered", selectAll != null)
        compose.runOnUiThread { selectAll!!.invoke() }
        compose.waitForIdle()
        compose.runOnUiThread { toolbar.copy!!.invoke() }
        compose.waitForIdle()

        val copied = clipboard.written.orEmpty()
        assertTrue("Prose is one selection across its paragraphs: \"$copied\"", copied.contains("Alpha item"))
        assertTrue("…including the second one: \"$copied\"", copied.contains("Bravo item"))
    }

    /**
     * A line with a link used to have no container at all: a hold there raised nothing.
     *
     * The tap handler still owns the short press (a link opens), so this also pins that the two
     * gestures were not swapped: the hold selects, and it does not open the link.
     */
    @Test
    fun aLineWithALinkCanBeSelectedToo() {
        render("A sentence with a [link](https://example.com) inside it.")

        hold("A sentence with a")

        assertTrue("A line carrying a link must be selectable, not only tappable", toolbar.shown)
    }

    /** A tap still opens the link: the selection must not have taken the short press away. */
    @Test
    fun aShortTapOnALineWithALinkDoesNotStartASelection() {
        render("A sentence with a [link](https://example.com) inside it.")

        compose.onNodeWithText("A sentence with a", substring = true, useUnmergedTree = true)
            .performTouchInput { click() }
        compose.waitForIdle()

        assertFalse("A tap is not a selection", toolbar.shown)
    }
}
