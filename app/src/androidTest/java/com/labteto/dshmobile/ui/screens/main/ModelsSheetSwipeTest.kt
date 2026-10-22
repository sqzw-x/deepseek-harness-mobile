package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.ModelCatalogModel
import com.labteto.dshmobile.core.wire.dto.ModelProviderGroup
import com.labteto.dshmobile.core.wire.dto.ModelSelection
import com.labteto.dshmobile.core.wire.dto.SessionModelsValue
import com.labteto.dshmobile.ui.components.DsPickerSheet
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Gestures on the model picker, which is a Material bottom sheet.
 *
 * Material hands the sheet every vertical drag its content does not use, so a downward swipe at
 * the top of the list dismissed the picker, and a short one left the sheet springing back while
 * the next upward swipe pulled the sheet instead of the list. The swipe surface now belongs to
 * the body: only the header carries the sheet's own drag-to-dismiss.
 */
class ModelsSheetSwipeTest {
    @get:Rule val compose = createComposeRule()

    private var dismissed = false
    private lateinit var scrollState: ScrollState

    private fun catalog(models: Int): SessionModelsValue = SessionModelsValue(
        current = ModelSelection(provider = "p", model = "m0"),
        routable = true,
        groups = listOf(
            ModelProviderGroup(
                id = "p",
                name = "Provider",
                models = (0 until models).map {
                    ModelCatalogModel(id = "m$it", name = "Model $it", description = "Number $it")
                },
            ),
        ),
    )

    private fun open(models: Int) {
        dismissed = false
        compose.setContent {
            DshTheme {
                scrollState = rememberScrollState()
                DsPickerSheet(title = "Models", onDismiss = { dismissed = true }) {
                    ModelsSheetBody(catalog(models), scrollState) { _, _, _ -> }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Model 0").assertIsDisplayed()
    }

    private fun list() = compose.onNode(hasScrollAction())

    @Test
    fun swipingDownAtTheTopDoesNotCloseThePicker() {
        open(models = 20)

        repeat(3) {
            list().performTouchInput { swipeDown() }
            compose.waitForIdle()
        }

        assertFalse("a downward swipe in the list dismissed the sheet", dismissed)
        compose.onNodeWithText("Models").assertIsDisplayed()
    }

    @Test
    fun swipingUpScrollsTheList() {
        open(models = 20)

        list().performTouchInput { swipeUp() }
        compose.waitForIdle()

        compose.onNodeWithText("Model 0").assertIsNotDisplayed()
        assertFalse("scrolling the list dismissed the sheet", dismissed)
    }

    @Test
    fun swipingDownAfterScrollingReturnsToTheTopWithoutClosing() {
        open(models = 20)

        list().performTouchInput { swipeUp() }
        compose.waitForIdle()
        list().performTouchInput { swipeDown() }
        compose.waitForIdle()

        compose.onNodeWithText("Model 0").assertIsDisplayed()
        assertFalse("returning to the top dismissed the sheet", dismissed)
    }

    @Test
    fun aTapOutsideClosesThePicker() {
        open(models = 20)

        val close = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.common_close)
        compose.onNodeWithContentDescription(close).performSemanticsAction(SemanticsActions.OnClick) {
            it()
        }
        compose.waitForIdle()

        assertTrue("the scrim did not dismiss the sheet", dismissed)
    }

    @Test
    fun aListSwipeRightAfterAPartialHeaderDragStillScrolls() {
        open(models = 40)

        compose.onNodeWithText("Models").performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 80f, durationMillis = 500)
        }
        list().performTouchInput { swipeUp() }
        compose.waitForIdle()

        assertFalse("a partial header drag dismissed the sheet", dismissed)
        assertTrue("the list did not scroll after a header drag", scrollState.value > 0)
    }

    @Test
    fun swipingDownInTheLeftGutterDoesNotCloseThePicker() {
        open(models = 20)

        compose.onNode(hasSetTextAction()).performTouchInput {
            swipe(
                start = Offset(left - 20f, centerY),
                end = Offset(left - 20f, centerY + 700f),
                durationMillis = 300,
            )
        }
        compose.waitForIdle()

        assertFalse("a downward swipe in the gutter dismissed the sheet", dismissed)
        compose.onNodeWithText("Models").assertIsDisplayed()
    }

    @Test
    fun twoQuickListSwipesBothScroll() {
        open(models = 40)

        list().performTouchInput { swipeUp() }
        val afterFirst = scrollState.value
        compose.mainClock.advanceTimeBy(100)
        list().performTouchInput { swipeUp() }
        compose.waitForIdle()

        assertTrue(
            "second quick swipe was lost: $afterFirst -> ${scrollState.value}",
            scrollState.value > afterFirst + 400,
        )
        assertFalse(dismissed)
    }

    @Test
    fun aBodyDragDuringAListFlingStillScrolls() {
        open(models = 40)

        list().performTouchInput { swipeUp() }
        val afterFirst = scrollState.value
        compose.mainClock.advanceTimeBy(100)
        compose.onNode(hasSetTextAction()).performTouchInput {
            swipeUp(startY = centerY, endY = centerY - 500f)
        }
        compose.waitForIdle()

        assertTrue(
            "body drag during the fling was lost: $afterFirst -> ${scrollState.value}",
            scrollState.value > afterFirst + 300,
        )
        assertFalse(dismissed)
    }

    @Test
    fun swipingDownOnTheSearchFieldDoesNotCloseThePicker() {
        open(models = 20)

        compose.onNode(hasSetTextAction()).performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 500f)
        }
        compose.waitForIdle()

        assertFalse("a downward swipe on the search field dismissed the sheet", dismissed)
        compose.onNodeWithText("Models").assertIsDisplayed()
    }

    @Test
    fun draggingUpFromTheSearchFieldScrollsTheList() {
        open(models = 20)

        compose.onNode(hasSetTextAction()).performTouchInput {
            swipeUp(startY = centerY, endY = centerY - 500f)
        }
        compose.waitForIdle()

        compose.onNodeWithText("Model 0").assertIsNotDisplayed()
        assertFalse(dismissed)
    }

    @Test
    fun aShortListIgnoresDownwardSwipes() {
        open(models = 2)

        compose.onNodeWithText("Model 0").performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 500f)
        }
        compose.waitForIdle()

        assertFalse("a downward swipe on a short list dismissed the sheet", dismissed)
        compose.onNodeWithText("Model 1").assertIsDisplayed()
    }

    @Test
    fun draggingTheHeaderDownClosesThePicker() {
        open(models = 20)

        compose.onNodeWithText("Models").performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 700f)
        }
        compose.waitForIdle()

        assertTrue("dragging the header did not dismiss the sheet", dismissed)
    }
}
