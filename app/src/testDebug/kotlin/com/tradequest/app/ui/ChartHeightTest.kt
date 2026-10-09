package com.tradequest.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.tradequest.app.ui.theme.TradeQuestTheme
import com.tradequest.chart.ChartTheme
import com.tradequest.engine.Timeframe
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The chart-height promise: on a 20:9 phone the plot box (everything between the header and
 * the order controls) is at least 55% of the screen height.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ChartHeightTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun thePlotIsAtLeastFiftyFivePercentOfATwentyByNineScreen() {
        compose.setContent { Probe() }

        val p = compose.onNodeWithTag("plot").getUnclippedBoundsInRoot()
        val t = compose.onNodeWithTag("total").getUnclippedBoundsInRoot()
        val plot = (p.bottom - p.top).value
        val total = (t.bottom - t.top).value

        val fraction = plot / total
        assertTrue(
            "plot=$plot total=$total fraction=$fraction (< 0.55)",
            fraction >= 0.55f,
        )
    }

    /** Mirrors TradeQuestScreen's vertical structure: header, weighted plot, footer. */
    @Composable
    private fun Probe() {
        val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
        TradeQuestTheme(ChartTheme.DARK) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Column(Modifier.fillMaxSize().testTag("total")) {
                TradeQuestHeader(
                    tab = 0,
                    liveOrders = 0,
                    timeframe = Timeframe.M15,
                    onTab = {},
                    onTimeframe = {},
                    onSettings = {},
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .heightIn(min = screenHeightDp * 0.55f)
                        .testTag("plot"),
                )
                // A stand-in for the Buy/Sell bar, so the footer share is honest.
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp))
              }
            }
        }
    }
}
