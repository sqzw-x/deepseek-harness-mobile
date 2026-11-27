package com.labteto.dshmobile.ui.input

import androidx.compose.ui.text.TextRange

/*
 * Which caret the full-screen composer steps to so that the text field scrolls back to it.
 *
 * The page insets its content by the height of the keyboard rather than being resized by the
 * window, so a keyboard arriving only ever shortens the field. Foundation clips the text to the
 * field and keeps whatever scroll offset it held, clamped into the shorter range: the lines that
 * were visible stay visible and the caret, if it was below them, goes with the hidden part. Its
 * one hook for moving the text is the selection, because the caret's rectangle lives inside the
 * field and is not reachable from outside.
 *
 * So the composer steps the caret onto the newline before it for a frame. The field scrolls that
 * line into view, which puts the caret's own line into view with it, and the frame after puts the
 * caret back. Two cases get no step at all: a caret already on the first line, where the clamp
 * already leaves it correct, and a selection that is not a caret, where there is no single line to
 * show and the field's own handle scrolling is the right answer.
 */
internal object CaretRestore {

    /** The selection to hold for one frame, or null when this caret needs no help. */
    fun stepTo(caret: Int, selection: TextRange, text: String): Int? {
        if (selection.start != selection.end) return null
        val at = caret.coerceIn(0, text.length)
        // No newline before the caret means it is on the first line, which is what a clamp from
        // any offset still shows. Stepping there would only ask the field to scroll up.
        return text.lastIndexOf('\n', at - 1).takeIf { it >= 0 }
    }
}
