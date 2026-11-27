package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The scroller the transcript puts around its rows leaves text selectable.
 *
 * [StableSelectionAutoScroll] installs a bring-into-view spec of its own. That only changes how far
 * one frame of a selection's scrolling may go, but it sits between the rows and the list, so this is
 * the guard that a hold inside it still starts a selection and raises the platform toolbar.
 */
class SelectionAutoScrollInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private class RecordingToolbar : TextToolbar {
        var shown = false
        override val status: TextToolbarStatus get() = if (shown) TextToolbarStatus.Shown else TextToolbarStatus.Hidden
        override fun hide() { shown = false }
        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) { shown = true }
    }

    private val toolbar = RecordingToolbar()

    private val paragraph = "A paragraph long enough to hold a word in, inside a scrolling list."

    @Composable
    private fun Transcript() {
        DshTheme {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                StableSelectionAutoScroll {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items((0 until 12).map { "Row $it of $paragraph" }) { row ->
                            SelectionContainer { Text(row) }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun aHoldInsideTheScrollerStillSelects() {
        compose.setContent { Transcript() }
        compose.waitForIdle()

        compose.onNodeWithText("Row 0 of", substring = true, useUnmergedTree = true)
            .performTouchInput { longClick() }
        compose.waitForIdle()

        assertTrue("A hold inside the bounded scroller must still raise the toolbar", toolbar.shown)
    }
}
