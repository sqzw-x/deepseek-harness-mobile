package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * The app's sheet surface, themed to the harness tokens.
 *
 * Sheets rather than dialogs for pickers: they arrive from the thumb's end of the screen, size
 * themselves to their content, and let a long list scroll without fighting a fixed-height plate.
 * [trailing] holds an optional action aligned with the title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DsBottomSheet(
    title: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DsTheme.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        modifier = modifier,
        shape = DsShapes.dialog,
        containerColor = colors.bgLayer2,
        scrimColor = colors.overlayMask,
        dragHandle = null,
        contentWindowInsets = { WindowInsets.navigationBars },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DsSpacing.large, vertical = DsSpacing.comfortable),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            // A short grabber stands in for the platform drag handle so the sheet still reads as
            // draggable without the default's heavy vertical padding.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Spacer(
                    Modifier
                        .fillMaxWidth(0.12f)
                        .height(4.dp)
                        .clip(DsShapes.pillFull)
                        .background(colors.borderL3),
                )
            }
            if (title != null) {
                Row(
                    Modifier.fillMaxWidth().padding(top = DsSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = DsType.large20, color = colors.labelPrimary)
                        if (subtitle != null) {
                            Text(subtitle, style = DsType.caption11, color = colors.labelTertiary)
                        }
                    }
                    trailing?.invoke()
                }
            }
            content()
            Spacer(Modifier.height(DsSpacing.small))
        }
    }
}

/**
 * The padded, swiped body of a picker sheet.
 *
 * It brings the sheet's gutters and bottom inset itself, so a swipe that starts in them belongs to
 * the body instead of the window. Scrollables inside still scroll; at their edges the leftover is
 * swallowed, and a drag on the search field or the gutters moves the same list. [scrollState] is
 * that list's state; when absent those drags do nothing. Starting a body drag also cancels a fling
 * the list still has running, so a second quick swipe is not spent stopping the first one.
 *
 * This exists because Material's sheet loans its own drag gesture to any content drag the list
 * cannot use, and to every content touch while that sheet is animating. A picker that wants the
 * list to own every swipe uses this body in a sheet that has no content drag at all
 * ([DsPickerSheet]).
 */
@Composable
fun DsSheetBody(
    modifier: Modifier = Modifier,
    scrollState: ScrollState? = null,
    contentPadding: PaddingValues = PaddingValues(
        start = DsSpacing.large,
        top = DsSpacing.small,
        end = DsSpacing.large,
        bottom = DsSpacing.comfortable + DsSpacing.small,
    ),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .nestedScroll(remember { SheetBodyNestedScroll() })
            .draggable(
                state = rememberDraggableState { delta -> scrollState?.dispatchRawDelta(-delta) },
                orientation = Orientation.Vertical,
                onDragStarted = { scrollState?.scroll(MutatePriority.UserInput) { } },
            )
            .padding(contentPadding)
            .windowInsetsPadding(WindowInsets.navigationBars),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        content = content,
    )
}

/**
 * Swallows the vertical scroll a list could not use at its edge.
 *
 * Without this a parent sheet's own nested-scroll connection could receive that delta and move the
 * sheet, which is the dismiss gesture reported from the model picker. Horizontal deltas are left
 * alone: nothing in a sheet body scrolls horizontally, and eating them would only hide mistakes.
 */
private class SheetBodyNestedScroll : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset = Offset(0f, available.y)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(0f, available.y)
}
