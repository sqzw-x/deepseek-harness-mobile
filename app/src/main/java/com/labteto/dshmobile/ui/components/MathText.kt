package com.labteto.dshmobile.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * LaTeX, drawn by JLaTeXMath's Android port.
 *
 * The library returns a [Drawable] that already knows the size it wants, which is what makes both
 * shapes fit the renderer as it stands: a display formula as a block of its own, and an inline one
 * through the same inline-content slot the workspace pills use. TeX that does not parse is shown
 * as its own source rather than dropped — a reply is not a place to hide text.
 */

/**
 * Rendered formulas, kept across screens.
 *
 * JLaTeXMath parses and lays out TeX on a canvas, which costs tens of milliseconds per formula —
 * measured at ~1.9s for twenty of them on one screen. The composable-level `remember` next to each
 * call cannot help there: switching sessions builds new composables, so every formula is rendered
 * again for content that has not changed. A drawable is immutable once built, so the same one can
 * be handed to any number of painters.
 *
 * Bounded because a drawable holds a bitmap: a long transcript of formulas should not grow without
 * limit in a process that lives for days.
 */
private val mathDrawables = object : LinkedHashMap<String, Drawable?>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Drawable?>): Boolean =
        size > MAX_CACHED_FORMULAS
}

private const val MAX_CACHED_FORMULAS = 48

/** Renders [tex] at [sizePx], or null when it is not valid TeX. */
internal fun mathDrawable(tex: String, sizePx: Float, color: Color): Drawable? {
    val key = "$sizePx|${color.value}|$tex"
    synchronized(mathDrawables) {
        // `containsKey` rather than a null check: failed TeX is worth remembering too, so a reply
        // full of it does not re-parse on every scroll.
        if (mathDrawables.containsKey(key)) return mathDrawables[key]
    }
    val drawable = com.labteto.dshmobile.diag.RenderStats.math {
        mathDrawableUnmeasured(tex, sizePx, color)
    }
    synchronized(mathDrawables) { mathDrawables[key] = drawable }
    return drawable
}

private fun mathDrawableUnmeasured(tex: String, sizePx: Float, color: Color): Drawable? = runCatching {
    JLatexMathDrawable.builder(tex)
        .textSize(sizePx)
        .color(color.toArgb())
        .align(JLatexMathDrawable.ALIGN_CENTER)
        .build()
}.getOrNull()

private class DrawablePainter(private val drawable: Drawable) : Painter() {
    override val intrinsicSize: Size
        get() = Size(
            drawable.intrinsicWidth.coerceAtLeast(1).toFloat(),
            drawable.intrinsicHeight.coerceAtLeast(1).toFloat(),
        )

    override fun DrawScope.onDraw() {
        drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
        drawable.draw(drawContext.canvas.nativeCanvas)
    }
}

@Composable
internal fun mathPainter(drawable: Drawable): Painter = remember(drawable) { DrawablePainter(drawable) }

/**
 * A display formula, centred on a line of its own. A formula wider than the screen scrolls
 * sideways instead of being scaled into illegibility.
 */
@Composable
internal fun MathBlock(tex: String, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    val sizePx = with(LocalDensity.current) { 17.sp.toPx() }
    val drawable = remember(tex, sizePx, colors.labelPrimary) { mathDrawable(tex, sizePx, colors.labelPrimary) }
    if (drawable == null) {
        Text(
            tex,
            style = DsType.mdCode.copy(color = colors.labelPrimary),
            modifier = modifier.fillMaxWidth(),
        )
        return
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Image(
                painter = mathPainter(drawable),
                contentDescription = tex,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

/** An inline formula, sized to sit in a line of text at [sizePx]. */
@Composable
internal fun InlineMath(tex: String, sizePx: Float) {
    val colors = DsTheme.colors
    val drawable = remember(tex, sizePx, colors.labelPrimary) { mathDrawable(tex, sizePx, colors.labelPrimary) }
    if (drawable == null) {
        Text(tex, style = DsType.mdCode.copy(color = colors.labelPrimary))
        return
    }
    Image(painter = mathPainter(drawable), contentDescription = tex)
}
