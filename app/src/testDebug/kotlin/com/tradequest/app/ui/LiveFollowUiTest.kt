package com.tradequest.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.tradequest.chart.ChartController
import com.tradequest.chart.ChartPanel
import com.tradequest.engine.Candle
import com.tradequest.engine.Timeframe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * UI-level guard for the live-follow bug: with no user gesture the "Jump to latest" badge
 * (and its "N new" counter) must never appear while minute candles tick in — the newest
 * candle stays in view. The chart is built the way the app builds it: empty, laid out,
 * then filled via `replaceData`.
 */
@RunWith(RobolectricTestRunner::class)
class LiveFollowUiTest {

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
    fun noGestureOverFourMinutesNeverShowsJumpToLatestOrBacklog() {
        val controller = ChartController(emptyList(), emptyList(), Timeframe.M1)
        rule.setContent { ChartPanel(controller = controller, modifier = Modifier.fillMaxSize()) }
        rule.waitForIdle()
        controller.replaceData(series(500), emptyList())
        rule.waitForIdle()

        var last = controller.state.m1.last()
        repeat(4) {
            last = Candle(last.ts + 60_000L, last.c, last.c + 1.0, last.c - 1.0, last.c + 0.5, 1.0)
            controller.appendM1(last, last.ts)
            rule.waitForIdle()
            // The badge is only drawn while following is off; it must stay absent.
            rule.onNodeWithText("Jump to latest").assertDoesNotExist()
            rule.onNodeWithText("4 new").assertDoesNotExist()
        }
    }

    @Test
    fun fitButtonIsAlwaysAvailableAndDoesNotCrash() {
        val controller = ChartController(series(500), emptyList(), Timeframe.M1)
        rule.setContent { ChartPanel(controller = controller, modifier = Modifier.fillMaxSize()) }
        rule.waitForIdle()
        rule.onNodeWithText("Fit").assertIsDisplayed()
    }
}
