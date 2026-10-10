package com.tradequest.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.tradequest.app.ui.theme.TradeQuestTheme
import com.tradequest.chart.ChartTheme
import com.tradequest.data.OrderStatus
import com.tradequest.data.TradeOrder
import com.tradequest.engine.OrderType
import com.tradequest.engine.Side
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneId

/** A pending order must show when it was placed, in the replayed (displayed) time. */
@RunWith(RobolectricTestRunner::class)
class PositionsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val placedTs = 1_700_000_000_000L // 2023-11-14T22:13:20Z
    private val weekMs = 604_800_000L

    private fun pending() = TradeOrder(
        id = 1,
        seasonId = 1,
        type = OrderType.BUY_LIMIT,
        side = Side.LONG,
        lots = 0.10,
        entryPrice = 2400.0,
        sl = 2395.0,
        tp = 2410.0,
        trailingDist = null,
        status = OrderStatus.PENDING,
        openedAt = placedTs,
        closedAt = null,
        closePrice = null,
        pnl = null,
        fees = null,
        tag = null,
        note = null,
    )

    @Test
    fun pendingCardShowsPlacedTimeWithDisplayOffset() {
        val offset = 4 * weekMs
        val expected = Instant.ofEpochMilli(placedTs + offset)
            .atZone(ZoneId.systemDefault())
            .let {
                String.format(
                    "%04d-%02d-%02d %02d:%02d",
                    it.year, it.monthValue, it.dayOfMonth, it.hour, it.minute,
                )
            }
        compose.setContent {
            TradeQuestTheme(ChartTheme.DARK) {
                PositionsScreen(
                    orders = listOf(pending()),
                    bid = 2400.0,
                    displayOffsetMs = offset,
                    onClose = { _, _ -> },
                    onCancel = {},
                    onEditStops = { _, _, _ -> },
                )
            }
        }
        compose.onNodeWithText("Placed $expected").assertIsDisplayed()
    }

    @Test
    fun queuedMarketOrderShowsFillsWhenMarketOpens() {
        val queued = pending().copy(type = OrderType.MARKET, entryPrice = null, status = OrderStatus.QUEUED)
        compose.setContent {
            TradeQuestTheme(ChartTheme.DARK) {
                PositionsScreen(
                    orders = listOf(queued),
                    bid = 2400.0,
                    onClose = { _, _ -> },
                    onCancel = {},
                    onEditStops = { _, _, _ -> },
                )
            }
        }
        compose.onNodeWithText("Queued, fills when market opens").assertIsDisplayed()
    }
}
