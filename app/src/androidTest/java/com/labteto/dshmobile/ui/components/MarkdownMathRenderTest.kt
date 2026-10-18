package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A drawn formula is inline content, so it survives only where the renderer hands its slots to the
 * `Text`. Each case below is a shape a real reply used and that came out as markdown source: a
 * paragraph with no link in it, a table cell, and a formula written inside bold.
 *
 * The assertions read the semantics tree rather than pixels: a slot that got its drawable carries
 * the TeX as its content description, and one that fell back to source does not.
 */
class MarkdownMathRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun render(markdown: String) {
        compose.setContent { DshTheme { MarkdownText(markdown, Modifier.width(340.dp)) } }
        compose.waitForIdle()
    }

    private fun tree() = compose.onRoot(useUnmergedTree = true).printToString()

    private fun assertFormulasDrawn(vararg tex: String) {
        val missing = tex.filter {
            compose.onAllNodesWithContentDescription(it, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        assertTrue("formulas that fell back to their source: $missing\n${tree()}", missing.isEmpty())
    }

    @Test
    fun formulasInAParagraphWithNoLinkInIt() {
        render("数值验证：\$e^{14}=1{,}202{,}604\$、\$2d_k^2/3=2730.7\$ 均精确吻合。")
        assertFormulasDrawn("e^{14}=1{,}202{,}604", "2d_k^2/3=2730.7")
    }

    @Test
    fun formulasInTableCells() {
        render("| 节 | 我补充的部分 |\n|---|---|\n| 1–2 | 统一判据；\$1/\\sqrt{d_k}\$ 的来源 |")
        assertFormulasDrawn("1/\\sqrt{d_k}")
    }

    @Test
    fun formulaInsideBold() {
        render("两条曲线**随 \$M\$ 增长反而下降**。")
        assertFormulasDrawn("M")
        assertTrue("bold printed its dollars: ${tree()}", !tree().contains("\$"))
    }
}
