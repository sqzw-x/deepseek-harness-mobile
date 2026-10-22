package com.labteto.dshmobile.ui.components

import android.graphics.drawable.ColorDrawable
import android.view.ViewParent
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A modal picker sheet whose content, and not the sheet, owns every vertical drag.
 *
 * Material's [androidx.compose.material3.ModalBottomSheet] loans its own drag to content the list
 * cannot consume, and it grabs the next touch outright while it is animating. A quick second swipe
 * therefore pulls or closes the sheet instead of the list. This sheet has no content drag at all:
 * the scrim, the back key and the header are the only ways out, and the body scrolls normally even
 * while the sheet is sliding in.
 *
 * The show/hide slide lives here rather than in a platform window animation so a touch during it
 * is handled by the content, not by a gesture that is still settling.
 */
@Composable
fun DsPickerSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DsTheme.colors
    val closeLabel = stringResource(R.string.common_close)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    var translation by remember { mutableFloatStateOf(screenHeightPx) }
    var sheetHeightPx by remember { mutableFloatStateOf(screenHeightPx) }
    var closing by remember { mutableStateOf(false) }
    var animation by remember { mutableStateOf<Job?>(null) }

    fun animateTo(target: Float) {
        animation?.cancel()
        animation = scope.launch {
            animate(translation, target, animationSpec = tween(220)) { value, _ -> translation = value }
        }
    }

    fun dismiss() {
        if (closing) return
        closing = true
        animation?.cancel()
        animation = scope.launch {
            animate(translation, sheetHeightPx, animationSpec = tween(200)) { value, _ ->
                translation = value
            }
            onDismiss()
        }
    }

    LaunchedEffect(Unit) { animateTo(0f) }

    Dialog(
        onDismissRequest = { dismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        val view = LocalView.current
        SideEffect {
            var parent: ViewParent? = view.parent
            while (parent != null && parent !is DialogWindowProvider) parent = parent.parent
            (parent as? DialogWindowProvider)?.window?.apply {
                setDimAmount(0f)
                setWindowAnimations(0)
                setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            }
        }
        Box(Modifier.fillMaxSize().imePadding()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.overlayMask)
                    .pointerInput(Unit) { detectTapGestures { dismiss() } }
                    .semantics {
                        contentDescription = closeLabel
                        onClick {
                            dismiss()
                            true
                        }
                    },
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // Keep taps on the sheet's blank areas from falling through to the scrim.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent()
                        }
                    }
                    .offset { IntOffset(0, translation.roundToInt()) }
                    .onSizeChanged { if (it.height > 0) sheetHeightPx = it.height.toFloat() }
                    .semantics { paneTitle = title },
                shape = DsShapes.dialog,
                color = colors.bgLayer2,
                shadowElevation = 8.dp,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .draggable(
                                state = rememberDraggableState { delta ->
                                    if (!closing) {
                                        animation?.cancel()
                                        translation = (translation + delta).coerceAtLeast(0f)
                                    }
                                },
                                orientation = Orientation.Vertical,
                                onDragStopped = { velocity ->
                                    val threshold = with(density) { 56.dp.toPx() }
                                    if (velocity > 250f || translation > threshold) {
                                        dismiss()
                                    } else {
                                        animateTo(0f)
                                    }
                                },
                            )
                            .padding(
                                start = DsSpacing.large,
                                top = DsSpacing.comfortable,
                                end = DsSpacing.large,
                            ),
                        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    ) {
                        // A short grabber stands in for the platform drag handle so the sheet
                        // still reads as draggable without the default's heavy padding.
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            Spacer(
                                Modifier
                                    .fillMaxWidth(0.12f)
                                    .height(4.dp)
                                    .clip(DsShapes.pillFull)
                                    .background(colors.borderL3),
                            )
                        }
                        Text(
                            title,
                            style = DsType.large20,
                            color = colors.labelPrimary,
                            modifier = Modifier.padding(top = DsSpacing.small),
                        )
                    }
                    content()
                }
            }
        }
    }
}
