package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

/**
 * How far one frame of a bring-into-view animation may scroll, in pixels.
 *
 * A selection handledrag asks the enclosing list to keep the dragged point on screen. That request
 * is refreshed on every pointer event, and the list measures it again on every frame of the
 * scrolling animation it starts. The distance between the dragged point and the edge of the
 * viewport is therefore also the size of the next request: drag once, let the list scroll, drag
 * again without letting go, and each repeat adds a whole viewport worth of scrolling. On a long
 * answer the selection grows by everything the text passes over on the way. Capping the distance
 * a request may be met with turns that runaway into a normal, slow edge scroll, and leaves a
 * genuine request - a drag that really did leave the viewport - served in a few frames instead of
 * one.
 */
internal const val MAX_SELECTION_AUTO_SCROLL_PER_FRAME = 24f

/**
 * The distance [list] asks for, reduced to at most [perFrameLimit] pixels per frame.
 *
 * Sign and zero are kept exactly as the list computed them: this only limits how fast a request is
 * served, never which direction it goes or whether it is served at all.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun limitedAutoScrollDistance(
    list: BringIntoViewSpec,
    offset: Float,
    size: Float,
    containerSize: Float,
    perFrameLimit: Float = MAX_SELECTION_AUTO_SCROLL_PER_FRAME,
): Float {
    val requested = list.calculateScrollDistance(offset, size, containerSize)
    if (perFrameLimit <= 0f) return requested
    return requested.coerceIn(-perFrameLimit, perFrameLimit)
}

/**
 * Bounds the automatic scrolling a text selection triggers inside a lazy list.
 *
 * Why this is needed at all, and why here rather than in the selection code: a selection handle is
 * a Popup of its own, so its drag never reaches the list as a scroll gesture. What moves the list
 * is the "keep the dragged position visible" request the selection manager sends up the
 * bring-into-view chain. Compose answers that chain with a [BringIntoViewSpec] read from this
 * composition local, and the answer is a distance, not a scroll: the list feeds it back into its
 * own animation, measures the result, and asks again next frame. Capping the distance here caps
 * that feedback loop at its source, for every scrollable ancestor at once, without touching the
 * list, its reversal, or the selection machinery.
 *
 * A handle drag is the only thing in a transcript that raises these requests, so the cap applies
 * to exactly the gesture this is about. Requests still resolve, so a drag that genuinely leaves
 * the viewport still scrolls to it - just at a speed a reader can follow and stop.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StableSelectionAutoScroll(content: @Composable () -> Unit) {
    val enclosing = LocalBringIntoViewSpec.current
    val bounded = remember(enclosing) { LimitedAutoScrollSpec(enclosing) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides bounded, content = content)
}

/** A [BringIntoViewSpec] that answers with [delegate]'s distance, capped. */
@OptIn(ExperimentalFoundationApi::class)
internal class LimitedAutoScrollSpec(private val delegate: BringIntoViewSpec) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        limitedAutoScrollDistance(delegate, offset, size, containerSize)
}
