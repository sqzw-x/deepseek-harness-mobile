package com.labteto.dshmobile.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Math, in both the shapes a model writes it: `$$…$$`/`\[…\]` as a block, `$…$`/`\(…\)` inside a
 * sentence. The tests that matter most here are the negative ones — a transcript full of correct
 * formulas is worth less than never turning a price into a formula.
 *
 * Every dollar is written escaped: it is a Kotlin template character, so `"$x"` would not compile.
 */
class MarkdownMathTest {

    private fun blocks(markdown: String) = parseMarkdown(markdown)

    private fun texOf(markdown: String): String = (blocks(markdown).single() as MdBlock.Math).tex

    // ---- Display math ---------------------------------------------------------

    @Test
    fun `a formula on one line is a math block`() {
        assertEquals("x^2 + y^2 = z^2", texOf("\$\$x^2 + y^2 = z^2\$\$"))
    }

    @Test
    fun `a formula on its own lines is a math block`() {
        assertEquals("\\frac{1}{2}", texOf("\$\$\n\\frac{1}{2}\n\$\$"))
    }

    @Test
    fun `a multi-line formula keeps its line breaks`() {
        assertEquals("a = b\nc = d", texOf("\$\$\na = b\nc = d\n\$\$"))
    }

    @Test
    fun `bracket delimiters work the same way`() {
        assertEquals("e^{i\\pi} + 1 = 0", texOf("\\[e^{i\\pi} + 1 = 0\\]"))
        assertEquals("\\int_0^1 x\\,dx", texOf("\\[\n\\int_0^1 x\\,dx\n\\]"))
    }

    @Test
    fun `prose around a formula keeps its order`() {
        val parsed = blocks("Before.\n\n\$\$\nE = mc^2\n\$\$\n\nAfter.")
        assertEquals(3, parsed.size)
        assertEquals(MdBlock.Paragraph(listOf("Before.")), parsed[0])
        assertEquals("E = mc^2", (parsed[1] as MdBlock.Math).tex)
        assertEquals(MdBlock.Paragraph(listOf("After.")), parsed[2])
    }

    @Test
    fun `a paragraph stops where a formula begins`() {
        val parsed = blocks("The identity is\n\$\$\n1 + 1 = 2\n\$\$")
        assertEquals(2, parsed.size)
        assertEquals(MdBlock.Paragraph(listOf("The identity is")), parsed[0])
        assertEquals("1 + 1 = 2", (parsed[1] as MdBlock.Math).tex)
    }

    @Test
    fun `an unclosed delimiter stays ordinary text`() {
        val parsed = blocks("\$\$\nx^2")
        assertEquals(1, parsed.size)
        assertEquals(MdBlock.Paragraph(listOf("\$\$", "x^2")), parsed[0])
    }

    @Test
    fun `trailing prose after a one-line formula keeps it out of math`() {
        val parsed = blocks("\$\$x^2\$\$ and then some prose")
        assertEquals(1, parsed.size)
        assertEquals(MdBlock.Paragraph(listOf("\$\$x^2\$\$ and then some prose")), parsed[0])
    }

    @Test
    fun `a formula inside a code fence is code`() {
        val parsed = blocks("```\n\$\$x^2\$\$\n```")
        assertEquals(1, parsed.size)
        assertEquals(MdBlock.Code(null, "\$\$x^2\$\$"), parsed[0])
    }

    @Test
    fun `prices in prose are not math`() {
        val parsed = blocks("It costs \$5 and \$10 in total.")
        assertEquals(1, parsed.size)
        assertEquals(MdBlock.Paragraph(listOf("It costs \$5 and \$10 in total.")), parsed[0])
    }

    // ---- Inline math ----------------------------------------------------------

    @Test
    fun `an inline formula is a math segment`() {
        assertEquals(
            listOf(
                InlineSegment.Plain("the identity "),
                InlineSegment.Math("e^{i\\pi} + 1 = 0"),
                InlineSegment.Plain(" holds"),
            ),
            parseInlineSegments("the identity \$e^{i\\pi} + 1 = 0\$ holds"),
        )
    }

    @Test
    fun `parenthesised and doubled delimiters are math too`() {
        assertEquals(listOf(InlineSegment.Math("a+b")), parseInlineSegments("\\(a+b\\)"))
        assertEquals(listOf(InlineSegment.Math("a+b")), parseInlineSegments("\$\$a+b\$\$"))
    }

    @Test
    fun `a lone dollar in prose stays prose`() {
        assertEquals(
            listOf(InlineSegment.Plain("costs \$5 and \$10")),
            parseInlineSegments("costs \$5 and \$10"),
        )
    }

    @Test
    fun `spaces inside the delimiters disqualify a formula`() {
        assertEquals(listOf(InlineSegment.Plain("a \$ x \$ b")), parseInlineSegments("a \$ x \$ b"))
    }

    @Test
    fun `an escaped dollar is a literal dollar`() {
        assertEquals(listOf(InlineSegment.Plain("\$5")), parseInlineSegments("\\\$5"))
    }

    @Test
    fun `a dollar inside a code span is code`() {
        assertEquals(listOf(InlineSegment.Code("\$x\$")), parseInlineSegments("`\$x\$`"))
    }

    @Test
    fun `an unclosed dollar stays prose`() {
        assertEquals(listOf(InlineSegment.Plain("a \$x")), parseInlineSegments("a \$x"))
    }
}
