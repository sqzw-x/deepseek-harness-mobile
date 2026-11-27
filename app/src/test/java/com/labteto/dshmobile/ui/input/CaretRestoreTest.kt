package com.labteto.dshmobile.ui.input

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which caret the full-screen composer steps to when the keyboard takes a third of the page.
 *
 * The rule exists because a text field only scrolls when its selection moves, and the page it sits
 * on shrinks around it rather than resizing it: without the step the field keeps showing the lines
 * that were visible before the keyboard arrived, with the caret hidden below them.
 */
class CaretRestoreTest {

    private fun step(text: String, caret: Int, selection: TextRange = TextRange(caret)) =
        CaretRestore.stepTo(caret, selection, text)

    @Test
    fun `a caret deep in the draft steps to the start of its own line`() {
        // "one\ntwo\nthree": the caret at the end of "three" must land on the newline that opens
        // that line, so the field scrolls that line — and with it the caret — into view.
        val text = "one\ntwo\nthree"
        assertEquals(text.lastIndexOf('\n'), step(text, text.length))
    }

    @Test
    fun `a caret at the start of a line steps onto the newline before it`() {
        // Typing at the head of a line still has to bring that line up: the newline before it is
        // the nearest thing on the previous line, and the field scrolls to it.
        val text = "one\ntwo\nthree"
        val caret = text.indexOf("three")
        assertEquals(caret - 1, step(text, caret))
    }

    @Test
    fun `a caret on the first line is left alone`() {
        // Nothing is hidden above it: any clamp leaves the first line showing, and asking the field
        // to scroll would move text the person is currently reading.
        val text = "one\ntwo\nthree"
        assertNull(step(text, 0))
        assertNull(step(text, 2))
    }

    @Test
    fun `an empty draft has nothing to scroll`() {
        assertNull(step("", 0))
    }

    @Test
    fun `a selection rather than a caret is left to the field`() {
        // There is no single line to show, and the field's own selection handles already scroll.
        val text = "one\ntwo\nthree"
        assertNull(step(text, text.length, TextRange(0, text.length)))
    }

    @Test
    fun `a caret past the end of the text is read as the end of it`() {
        // An edit landing between the draft and the selection it was written with must not turn
        // the step into an exception; the text field would clamp the same way.
        val text = "one\ntwo"
        assertEquals(text.lastIndexOf('\n'), step(text, text.length + 5))
    }
}
