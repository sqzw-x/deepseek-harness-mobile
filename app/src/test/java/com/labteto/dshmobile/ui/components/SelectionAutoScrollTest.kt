package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a selection drag scrolls a lazy list: the distance a bring-into-view request may be served
 * with.
 *
 * The bug this guards against: a list answers each request with the whole gap between the dragged
 * point and the viewport edge, then measures again on the next frame, so one drag turned into a
 * fast upward scroll of the transcript and selected everything it passed. The cap has to stop that
 * without ever changing which way a request is served, or dropping it.
 *
 * The list itself is stubbed, since the only thing under test is what this code does to the answer
 * it is given; the real arithmetic is the framework's and is already covered there.
 */
class SelectionAutoScrollTest {

    @OptIn(ExperimentalFoundationApi::class)
    private class StubList(private val answer: Float) : BringIntoViewSpec {
        var calls = 0
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
            calls++
            return answer
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun limited(answer: Float, limit: Float = 24f): Float {
        val stub = StubList(answer)
        val result = limitedAutoScrollDistance(stub, 0f, 0f, 0f, limit)
        assertEquals("the list must still be asked exactly once", 1, stub.calls)
        return result
    }

    @Test
    fun `a long jump is shortened but keeps its direction`() {
        assertEquals(24f, limited(400f), 0.0001f)
        assertEquals(24f, limited(24.5f), 0.0001f)
    }

    @Test
    fun `scrolling the other way is shortened the same way`() {
        assertEquals(-24f, limited(-400f), 0.0001f)
        assertEquals(-24f, limited(-24.5f), 0.0001f)
    }

    @Test
    fun `a request already within the cap is left alone`() {
        assertEquals(0f, limited(0f), 0.0001f)
        assertEquals(3.5f, limited(3.5f), 0.0001f)
        assertEquals(-12f, limited(-12f), 0.0001f)
        assertEquals(24f, limited(24f), 0.0001f)
    }

    @Test
    fun `the cap never turns a request into no request`() {
        // Anything the list wanted to scroll still scrolls; only the distance changed. A request
        // smaller than one pixel is served as that same fraction of a pixel rather than rounded
        // away to nothing, which is what keeps the last frame of a scroll from stalling.
        listOf(0.5f, -0.5f, 1e6f, -1e6f).forEach { answer ->
            val served = limited(answer)
            assertTrue("a non-zero request must stay non-zero", served != 0f)
            assertEquals("a non-zero request must keep its sign", answer > 0f, served > 0f)
        }
        assertEquals(0.5f, limited(0.5f), 0.0001f)
        assertEquals(-0.5f, limited(-0.5f), 0.0001f)
    }

    @Test
    fun `a cap of zero or less disables the limit rather than freezing the list`() {
        assertEquals(400f, limited(400f, limit = 0f), 0.0001f)
        assertEquals(-400f, limited(-400f, limit = -1f), 0.0001f)
    }
}
