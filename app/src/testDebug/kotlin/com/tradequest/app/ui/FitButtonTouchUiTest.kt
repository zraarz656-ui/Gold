package com.tradequest.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.tradequest.chart.ChartController
import com.tradequest.chart.ChartPanel
import com.tradequest.engine.Candle
import com.tradequest.engine.Timeframe
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Bug 2: tapping "Fit" on the device did nothing. The handler resets the manual price scale,
 * so the failure is in the tap reaching the button — a real pointer tap is dispatched to the
 * root, unlike [androidx.compose.ui.test.SemanticsNodeInteraction.performClick], which calls
 * the clickable's semantics action directly and hides any sibling pointer-input interference
 * from the chart's own `pointerInput`.
 */
@RunWith(RobolectricTestRunner::class)
class FitButtonTouchUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun series(count: Int): List<Candle> {
        var price = 2400.0
        return (0 until count).map { i ->
            val o = price
            val c = o + if (i % 3 == 0) 0.5 else -0.3
            price = c
            Candle(1_700_000_000_000L + i * 60_000L, o, maxOf(o, c) + 0.3, minOf(o, c) - 0.3, c, 100.0)
        }
    }

    @Test
    fun aRealTouchOnFitRestoresTheAutoPriceScale() {
        val controller = ChartController(series(500), emptyList(), Timeframe.M1)
        rule.setContent { ChartPanel(controller = controller, modifier = Modifier.fillMaxSize()) }
        rule.waitForIdle()

        // Manual vertical scale, as if the user had dragged the price gutter.
        controller.scalePriceRange(2.0)
        rule.waitForIdle()
        assertTrue("manual scale should be active", controller.state.viewport.manualPriceScale)

        // Tap the Fit button with a genuine pointer event dispatched to the root.
        val fit = rule.onNodeWithText("Fit").fetchSemanticsNode().boundsInRoot
        val center = Offset((fit.left + fit.right) / 2f, (fit.top + fit.bottom) / 2f)
        rule.onRoot().performTouchInput { click(center) }
        rule.waitForIdle()

        assertFalse(
            "Fit must clear the manual price scale after a real touch",
            controller.state.viewport.manualPriceScale,
        )
    }

    @Test
    fun aRealGutterDragThenFitRestoresTheAutoRange() {
        val controller = ChartController(series(500), emptyList(), Timeframe.M1)
        rule.setContent { ChartPanel(controller = controller, modifier = Modifier.fillMaxSize()) }
        rule.waitForIdle()

        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        // Drag vertically in the right-hand price gutter to pin a manual range.
        val gutterX = root.right - 8f
        rule.onRoot().performTouchInput {
            down(Offset(gutterX, root.center.y))
            moveTo(Offset(gutterX, root.center.y - 120f))
            up()
        }
        rule.waitForIdle()
        assertTrue("gutter drag should pin a manual scale", controller.state.viewport.manualPriceScale)
        val before = controller.currentPriceRange()

        val fit = rule.onNodeWithText("Fit").fetchSemanticsNode().boundsInRoot
        rule.onRoot().performTouchInput {
            click(Offset((fit.left + fit.right) / 2f, (fit.top + fit.bottom) / 2f))
        }
        rule.waitForIdle()

        assertFalse("Fit clears the manual scale", controller.state.viewport.manualPriceScale)
        val after = controller.currentPriceRange()
        // Auto fit re-derives the range from the visible candles, so it differs from the
        // pinned one and matches the deterministic auto-fit of the visible bars.
        assertFalse("range should change back to the auto fit", after == before)
    }
}
