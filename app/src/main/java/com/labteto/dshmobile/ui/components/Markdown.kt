package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsColors
import com.labteto.dshmobile.ui.theme.DsShapes
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.core.markdown.SyntaxToken
import com.labteto.dshmobile.core.markdown.highlightCode
import com.labteto.dshmobile.diag.RenderStats
import com.labteto.dshmobile.core.markdown.isHighlighted
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.theme.Ds
import com.labteto.dshmobile.core.markdown.safeHttpUrl
import com.labteto.dshmobile.core.markdown.InlineSegment
import com.labteto.dshmobile.core.markdown.MdBlock
import com.labteto.dshmobile.core.markdown.parseInlineSegments
import com.labteto.dshmobile.core.markdown.parseMarkdown
import androidx.compose.ui.text.style.TextDecoration
import kotlin.math.pow

val LocalFileOpener = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * Where a workspace reference leads: a folder goes to the Files browser at that directory, a file
 * to the Preview. Defaults to the file opener so a host that registers only one still works.
 */
val LocalFolderOpener = staticCompositionLocalOf<((String) -> Unit)?> { null }

/**
 * Whether a workspace reference names a folder.
 *
 * A trailing slash says so outright. A model often omits it, so a last segment with no extension
 * is read as a folder too. That is only a first guess: `Makefile` and `LICENSE` are files, and
 * the Workspace opens them in Preview when the harness answers that they are not directories
 * (and `.github`, read as a file, goes to Files the same way).
 */
fun isFolderReference(path: String): Boolean {
    val trimmed = path.trim()
    if (trimmed.endsWith('/')) return true
    val last = trimmed.substringAfterLast('/')
    return last.isNotEmpty() && !last.contains('.')
}

/**
 * Block-level Markdown renderer: fenced code blocks, #-#### headings, bullet and
 * ordered lists, blockquotes, and paragraphs with inline **bold**, *italic*,
 * `code` chips and [links](https://example.com). Tables render as plain text.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    // The parser lives in :core (pure JVM) so it is unit-testable and survives a renderer rewrite.
    // List items arrive one per block, so runs of them are regrouped here: the renderer needs the
    // whole list at once to number an ordered list and to keep the markers in one column.
    val blocks = remember(text) {
        RenderStats.countChars(text.length)
        RenderStats.markdown { groupListBlocks(parseMarkdown(text)) }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { render ->
            when (render) {
                is RenderBlock.ListRun -> MdListBlock(render)
                is RenderBlock.Of -> when (val block = render.block) {
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> DsType.mdH1
                        2 -> DsType.mdH2
                        3 -> DsType.mdH3
                        else -> DsType.mdH4
                    }
                    InlineMarkdown(block.text, style.copy(color = colors.labelPrimary), Modifier.padding(top = 10.dp))
                }
                // A paragraph's line breaks are kept, as the web client keeps them: a model that
                // lists one file per line meant one file per line, and joining with a space made
                // `README.md src` out of two separate names.
                is MdBlock.Paragraph -> InlineMarkdown(
                    block.lines.joinToString("\n"),
                    DsType.mdBody.copy(color = colors.labelPrimary),
                    Modifier.fillMaxWidth(),
                )
                                is MdBlock.Blockquote -> MdBlockquote(block)
                is MdBlock.Code -> CodeBlock(block.lang, block.code)
                is MdBlock.Math -> MathBlock(block.tex)
                is MdBlock.HorizontalRule -> HorizontalRuleLine()
                    is MdBlock.Table -> MdTableBlock(block)
                    // Grouped away above; the parser never hands a bare item to the renderer.
                    is MdBlock.ListItem -> Unit
                }
            }
        }
    }
}


// ---- List grouping -----------------------------------------------------------

/**
 * A consecutive run of [MdBlock.ListItem]s, as one renderable block.
 *
 * The core parser emits one block per item because that is what the grammar produces; a renderer
 * needs the run so it can number an ordered list and align every marker in one column. The run is
 * broken by a blank line only — a nested item stays in its parent's run, carrying its own indent.
 */
private sealed interface RenderBlock {
    data class Of(val block: MdBlock) : RenderBlock
    data class ListRun(val items: List<MdBlock.ListItem>, val ordered: Boolean) : RenderBlock
}

/** Wrap runs of list items into [ListRun], leaving every other block untouched and in order. */
private fun groupListBlocks(blocks: List<MdBlock>): List<RenderBlock> {
    val out = mutableListOf<RenderBlock>()
    val run = mutableListOf<MdBlock.ListItem>()
    fun flush() {
        if (run.isEmpty()) return
        // Orderedness comes from the first item of the run; a list cannot be half-numbered.
        out += RenderBlock.ListRun(run.toList(), ordered = run.first().ordered)
        run.clear()
    }
    blocks.forEach { block ->
        if (block is MdBlock.ListItem) run += block
        else {
            flush()
            out += RenderBlock.Of(block)
        }
    }
    flush()
    return out
}

// ---- Inline rendering ------------------------------------------------------

/** Renders one line of markdown with bold/italic/code/link spans. */
@Composable
private fun InlineMarkdown(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    val codeStyle = style.copy(
        fontFamily = DsType.codeFont,
        color = colors.labelPrimary,
    )
    val result = remember(text, style, codeStyle, colors) {
        RenderStats.inline { buildInlineContent(text, codeStyle, colors) }
    }
    val openFile = LocalFileOpener.current
    val openFolder = LocalFolderOpener.current
    val uriHandler = LocalUriHandler.current
    val hasLinks = remember(result) {
        val count = result.getStringAnnotations("url", 0, result.length).size
        RenderStats.countLinks(count)
        count > 0
    }
    // Each pill has a slot of its own, as wide as its label at the pill's (slightly smaller, mono)
    // size plus icon and padding, so the line is laid out around exactly that pill. A label wider
    // than most of the screen is capped and ellipsized rather than run past the edge.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val maxPillWidth = LocalConfiguration.current.screenWidthDp.dp * 0.7f
    val pillStyle = remember(style) { style.copy(fontFamily = DsType.codeFont, fontSize = style.fontSize * 0.9f) }
    val mathSizePx = with(density) { style.fontSize.toPx() }
    val mathFallbackStyle = remember(style) { style.copy(fontFamily = DsType.codeFont) }
    val inlineContent = remember(result, pillStyle, density, maxPillWidth, colors, mathSizePx) {
        val slotStartedAt = android.os.SystemClock.uptimeMillis()
        val lineHeight = style.lineHeight.takeIf { it.isSp }?.let { it.value } ?: (style.fontSize.value * 1.5f)
        val pills = result.getStringAnnotations("path", 0, result.length).withIndex().associate { (index, ann) ->
            val label = result.text.substring(ann.start, ann.end)
            val width = with(density) {
                // 1dp of slack so rounding through sp never ellipsizes a label that fits.
                val natural = measurer.measure(label, pillStyle).size.width + (13 + 4 + 16 + 1).dp.roundToPx()
                minOf(natural, maxPillWidth.roundToPx()).toSp()
            }
            filePillId(index) to InlineTextContent(
                Placeholder(width, (lineHeight * 0.95f).sp, PlaceholderVerticalAlign.TextCenter),
            ) { FilePill(path = ann.item, label = label, style = style) }
        }
        // A formula gets exactly the slot its own drawable asks for; one that does not parse falls
        // back to its source text, measured so the line still lays out around it.
        val maths = result.getStringAnnotations(MATH_ANNOTATION, 0, result.length).withIndex().associate { (index, ann) ->
            val drawable = mathDrawable(ann.item, mathSizePx, colors.labelPrimary)
            val (width, height) = if (drawable != null) {
                with(density) { drawable.intrinsicWidth.toSp() to drawable.intrinsicHeight.toSp() }
            } else {
                val measured = measurer.measure(ann.item, mathFallbackStyle).size
                with(density) { measured.width.toSp() to measured.height.toSp() }
            }
            mathSlotId(index) to InlineTextContent(
                Placeholder(width, height, PlaceholderVerticalAlign.TextCenter),
            ) { InlineMath(tex = ann.item, sizePx = mathSizePx) }
        }
        RenderStats.addSlots(pills.size + maths.size, android.os.SystemClock.uptimeMillis() - slotStartedAt)
        pills + maths
    }
    if (!hasLinks) {
        // Nothing to tap, so the text is laid out plainly: a ClickableText would swallow the tap
        // that belongs to the row. The inline slots are still handed over — a formula is inline
        // content whether or not the line has a link in it, and a text without them draws each
        // slot's source instead.
        RenderStats.addSelection()
        // Plain text, not a selection container. Measured at ~300ms for the containers a single
        // screen needs, and the one dismissal mechanism this app has for them — a shared token
        // that re-keys each container — was never wired, so a selection could not be cleared at
        // all. The row's own copy action is the way to take text out of here.
        Text(result, style = style, inlineContent = inlineContent, modifier = modifier)
        return
    }
    // `Text` with its own hit test rather than `ClickableText`: the pills are inline content, which
    // ClickableText cannot host. Taps resolve to a character offset through the layout result,
    // exactly as ClickableText does internally. A pill is clickable itself: the offset under its
    // right half is the one after it, so an offset lookup would leave half of every pill dead.
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        result,
        modifier = modifier.pointerInput(result) {
            detectTapGestures { position ->
                val offset = layout?.getOffsetForPosition(position) ?: return@detectTapGestures
                if (result.getStringAnnotations("path", offset, offset).isNotEmpty()) return@detectTapGestures
                result.getStringAnnotations("url", offset, offset).firstOrNull()?.item?.let { raw ->
                    // http(s) goes to the browser, anything else is treated as a path into the
                    // session's own files. The scheme check lives in core so a reply cannot smuggle
                    // `intent:`/`file:` past this handler by dressing it up as a link.
                    val url = safeHttpUrl(raw)
                    if (url != null) runCatching { uriHandler.openUri(url) }
                    else com.labteto.dshmobile.ui.screens.main.previewPath(raw)?.let { openWorkspaceReference(it, openFile, openFolder) }
                }
            }
        },
        style = style,
        inlineContent = inlineContent,
        onTextLayout = { layout = it },
    )
}

private fun buildInlineContent(
    text: String,
    codeStyle: TextStyle,
    colors: DsColors,
): AnnotatedString = InlineSpans(codeStyle, colors).emit(parseInlineSegments(text)).toAnnotatedString()

/**
 * Walks the segment tree into one annotated string.
 *
 * Emphasis is descended into rather than appended: its content is a segment list of its own, so a
 * formula or a workspace pill written inside bold takes its slot from the same counters as one at
 * the top level. Those counters have to stay unique for the whole line — the layout pass finds a
 * slot by the id `buildInlineContent` gave it.
 */
private class InlineSpans(private val codeStyle: TextStyle, private val colors: DsColors) {
    private val builder = AnnotatedString.Builder()
    private var pills = 0
    private var maths = 0

    fun emit(segments: List<InlineSegment>): AnnotatedString.Builder {
        segments.forEach { segment ->
            when (segment) {
                is InlineSegment.Plain -> builder.append(segment.text)
                is InlineSegment.Bold -> builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    emit(segment.segments)
                }
                is InlineSegment.Italic -> builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    emit(segment.segments)
                }
                is InlineSegment.Strikethrough -> builder.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    emit(segment.segments)
                }
                is InlineSegment.Code -> builder.withStyle(
                    SpanStyle(fontFamily = codeStyle.fontFamily, color = codeStyle.color),
                ) { builder.append(segment.text) }
                is InlineSegment.Math -> {
                    // The TeX travels as an annotation so the layout pass can find the slots and give
                    // each one the size its formula actually wants.
                    builder.pushStringAnnotation(MATH_ANNOTATION, segment.tex)
                    builder.appendInlineContent(mathSlotId(maths++), segment.tex)
                    builder.pop()
                }
                is InlineSegment.Link -> {
                    val path = if (safeHttpUrl(segment.url) == null) com.labteto.dshmobile.ui.screens.main.previewPath(segment.url) else null
                    builder.pushStringAnnotation("url", segment.url)
                    if (path != null) {
                        // A path into the session's workspace is an artifact, not a web link: it is
                        // drawn as a pill (see [InlineMarkdown]'s inlineContent) so it reads as a file
                        // you can open, and never as a blue word that may go anywhere.
                        builder.pushStringAnnotation("path", path)
                        builder.appendInlineContent(filePillId(pills++), segment.text.ifBlank { path })
                        builder.pop()
                    } else {
                        builder.withStyle(SpanStyle(color = colors.accent)) { builder.append(segment.text) }
                    }
                    builder.pop()
                }
            }
        }
        return builder
    }
}

/** The inline-content slot of the [index]th workspace-path pill in a paragraph. */
private fun filePillId(index: Int) = "dsh.file-pill.$index"

/** Carries a formula's TeX through the annotated string so the layout pass can find its slots. */
private const val MATH_ANNOTATION = "math"

/** The inline-content slot of the [index]th formula in a paragraph. */
private fun mathSlotId(index: Int) = "dsh.math.$index"

/** Folders go to Files when the host registered a folder opener; everything else to Preview. */
private fun openWorkspaceReference(path: String, openFile: (String) -> Unit, openFolder: ((String) -> Unit)?) {
    if (isFolderReference(path) && openFolder != null) openFolder(path) else openFile(path)
}

/** A workspace path drawn as a pill: file or folder icon, then the path, on a bordered block. */
@Composable
private fun FilePill(path: String, label: String, style: TextStyle) {
    val colors = DsTheme.colors
    val folder = isFolderReference(path)
    val openFile = LocalFileOpener.current
    val openFolder = LocalFolderOpener.current
    Row(
        Modifier
            .clip(DsShapes.pill)
            .background(colors.bgModulePlatform)
            .border(1.dp, colors.borderL3, DsShapes.pill)
            .clickable(role = Role.Button) { openWorkspaceReference(path, openFile, openFolder) }
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            if (folder) FeatherIcons.Folder else FeatherIcons.FileText,
            null,
            Modifier.size(13.dp),
            tint = if (folder) colors.accent else colors.labelSecondary,
        )
        Text(
            label,
            style = style.copy(fontFamily = DsType.codeFont, fontSize = style.fontSize * 0.9f, color = colors.labelPrimary),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---- Block renderers --------------------------------------------------------

@Composable
private fun MdListBlock(block: RenderBlock.ListRun) {
    val colors = DsTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(start = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Numbering counts only top-level ordered items; a nested item restarts nothing.
        var ordinal = 0
        block.items.forEach { item ->
            if (item.indent == 0) ordinal++
            // Resolved once: `checked` is null for an ordinary item, and the marker, the tint and
            // the strikethrough all key off the same answer.
            val done = item.checked == true
            Row(
                Modifier
                    .fillMaxWidth()
                    // Nesting is indentation, not a second list: the parser already resolved depth.
                    .padding(start = (item.indent * 16).dp),
                verticalAlignment = Alignment.Top,
            ) {
                when {
                    // A task item's marker *is* its state, so the box replaces the bullet rather
                    // than sitting beside it — two marks for one item read as a nested list.
                    item.checked != null -> {
                        Text(
                            if (done) "\u2611" else "\u2610",
                            style = DsType.mdBody.copy(color = if (done) colors.success else colors.labelTertiary),
                            modifier = Modifier.width(18.dp),
                        )
                    }
                    block.ordered -> Text(
                        "$ordinal.",
                        style = DsType.mdBody.copy(color = colors.labelSecondary),
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(28.dp),
                    )
                    else -> Text(
                        "\u2022",
                        style = DsType.mdBody.copy(color = colors.labelSecondary),
                        modifier = Modifier.width(18.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                // A finished task reads as done: struck through, not just ticked.
                val style = if (done) {
                    DsType.mdBody.copy(color = colors.labelTertiary, textDecoration = TextDecoration.LineThrough)
                } else {
                    DsType.mdBody.copy(color = colors.labelPrimary)
                }
                InlineMarkdown(item.text, style, Modifier.weight(1f))
            }
        }
    }
}

/** A `---` rule: a hairline, inset to the text column so it does not touch the screen edges. */
@Composable
private fun HorizontalRuleLine() {
    val colors = DsTheme.colors
    Spacer(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(1.dp)
            .background(colors.borderL2),
    )
}

/**
 * A GFM table.
 *
 * Rendered as a real grid rather than as a run of text lines: the parser resolved per-column
 * alignment, and discarding it would put a numbers column on the same footing as prose. Cells
 * carry inline markup, so each goes through [InlineMarkdown].
 */
@Composable
private fun MdTableBlock(block: MdBlock.Table) {
    val colors = DsTheme.colors
    // Column widths follow the content instead of splitting evenly. Equal weights gave a one-digit
    // `#` column the same share as a prose column, so a table of numbers and text came out with a
    // wide empty first column and a cramped second. Weights here are proportional to the longest
    // cell each column holds, which is a single pass over the parsed table.
    val weights = remember(block) { tableColumnWeights(block) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(DsShapes.block)
            .border(1.dp, colors.borderL1, DsShapes.block),
    ) {
        Row(Modifier.fillMaxWidth().background(colors.codeBlockBanner)) {
            block.header.forEachIndexed { index, cell ->
                TableCell(
                    text = cell,
                    alignment = block.alignments.getOrNull(index) ?: MdBlock.Alignment.LEFT,
                    header = true,
                    modifier = Modifier.weight(weights.getOrElse(index) { 1f }),
                )
            }
        }
        block.rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) {
                Spacer(Modifier.fillMaxWidth().height(1.dp).background(colors.borderL1))
            }
            Row(Modifier.fillMaxWidth()) {
                row.forEachIndexed { index, cell ->
                    TableCell(
                        text = cell,
                        alignment = block.alignments.getOrNull(index) ?: MdBlock.Alignment.LEFT,
                        header = false,
                        modifier = Modifier.weight(weights.getOrElse(index) { 1f }),
                    )
                }
            }
        }
    }
}


/**
 * Per-column layout weights, proportional to each column's longest cell.
 *
 * The share is `width^0.65` rather than raw width: a straight ratio lets one long cell (a URL, an
 * English sentence in a table of numbers) take almost the whole line and squeeze every other column
 * to an ellipsis. Compressing the exponent keeps the ordering — wider content gets more room —
 * while bounding how much of the table any single column can claim.
 *
 * A floor of one character keeps the ratio finite; an empty column still gets a share.
 */
private fun tableColumnWeights(table: MdBlock.Table): List<Float> {
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (columns == 0) return emptyList()
    return (0 until columns).map { index ->
        val longest = sequence {
            yield(table.header.getOrNull(index))
            table.rows.forEach { yield(it.getOrNull(index)) }
        }.filterNotNull().maxOfOrNull { it.length } ?: 0
        maxOf(longest, 1).toDouble().pow(0.65).toFloat()
    }
}

@Composable
private fun TableCell(
    text: String,
    alignment: MdBlock.Alignment,
    header: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val textAlign = when (alignment) {
        MdBlock.Alignment.LEFT -> TextAlign.Start
        MdBlock.Alignment.CENTER -> TextAlign.Center
        MdBlock.Alignment.RIGHT -> TextAlign.End
    }
    Box(modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
        // A header cell is emphasis, not a different size: at mdSmall a bigger weight on the
        // header would out-weigh the body text it is labelling.
        val style = DsType.mdSmall.copy(
            color = if (header) colors.labelPrimary else colors.labelSecondary,
            fontWeight = if (header) FontWeight.Medium else FontWeight.Normal,
            textAlign = textAlign,
        )
        InlineMarkdown(text, style, Modifier.fillMaxWidth())
    }
}

@Composable
private fun MdBlockquote(block: MdBlock.Blockquote) {
    val colors = DsTheme.colors
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 2.dp)) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.dp))
                .background(colors.citation),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            block.lines.forEach { line ->
                InlineMarkdown(line, DsType.mdSmall.copy(color = colors.labelTertiary), Modifier.fillMaxWidth())
            }
        }
    }
}

/** Fenced code block with a sticky banner (lang · copy) and a mono pre. */
@Composable
private fun CodeBlock(lang: String?, code: String, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(DsShapes.block)
            .background(colors.codeBlockBg)
            .border(1.dp, colors.borderL1, DsShapes.block),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.codeBlockBanner)
                .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                lang ?: stringResource(R.string.tool_copy_code),
                style = DsType.caption11Strong.copy(fontFamily = DsType.codeFont, color = colors.labelCaption),
                color = colors.labelCaption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = stringResource(R.string.tool_copy_code),
                tint = colors.labelTertiary,
                modifier = Modifier
                    .size(16.dp)
                    .clip(DsShapes.chip)
                    .clickable { clipboard.setText(AnnotatedString(code)) }
                    .padding(2.dp),
            )
        }
        // Highlighting is resolved in the pure-JVM core lexer; this layer only maps its token
        // kinds to theme colors, so a merge that rewrites the renderer cannot break the tokenizer
        // and a theme change cannot break the lexer.
        val highlighted = remember(code, lang) { RenderStats.code { codeAnnotated(code, lang) } }
        Text(
            highlighted,
            style = DsType.mdCode,
            color = colors.labelPrimary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}


/**
 * Run the core lexer over [code] and paint each span with the theme's syntax color.
 *
 * Joining the lines back with `\n` is what keeps the result plain text: the renderer lays it out
 * as ordinary code with no per-line layout of its own, and the lexer guarantees the concatenation
 * reproduces the input exactly.
 */
private fun codeAnnotated(code: String, lang: String?): AnnotatedString {
    if (!isHighlighted(lang)) return AnnotatedString(code)
    val builder = AnnotatedString.Builder()
    highlightCode(code, lang).forEachIndexed { index, line ->
        if (index > 0) builder.append('\n')
        line.forEach { span ->
            // Syntax tints are a fixed palette, not theme roles: code reads the same in light and
            // dark, the way it does on the web client.
            val color = when (span.token) {
                SyntaxToken.Comment -> Ds.SyntaxComment
                SyntaxToken.String -> Ds.SyntaxString
                SyntaxToken.Number -> Ds.SyntaxConstant
                SyntaxToken.Keyword -> Ds.SyntaxKeyword
                SyntaxToken.Function -> Ds.SyntaxFunction
                SyntaxToken.Type -> Ds.SyntaxConstant
                SyntaxToken.Plain -> null
            }
            if (color == null) builder.append(span.text)
            else builder.withStyle(SpanStyle(color = color)) { append(span.text) }
        }
    }
    return builder.toAnnotatedString()
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun MarkdownTextPreview() {
    DshTheme {
        MarkdownText(
            text = """
                # Heading

                A paragraph with **bold**, *italic* and `inline code` plus a [link](https://example.com).

                - first item
                - second item

                1. ordered one
                2. ordered two

                > A quoted thought.

                ```kotlin
                val answer = 42
                ```

                | col a | col b |
                | ----- | ----- |
                | 1     | 2     |
            """.trimIndent(),
            modifier = Modifier.padding(16.dp),
        )
    }
}
