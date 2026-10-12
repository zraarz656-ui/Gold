package com.tradequest.app.ui

import com.tradequest.data.OrderRequest
import com.tradequest.engine.OrderType
import com.tradequest.engine.Side
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * "Closed trades vanish in 1-2 minutes": [TradingViewModel.history] was a sliding window
 * keyed on the last-processed candle, so older closed trades dropped out as the replayed
 * clock advanced. History must list every closed trade of the season, oldest first.
 */
@RunWith(RobolectricTestRunner::class)
class ClosedTradeHistoryTest {

    private val h = TradingHarness()

    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        h.setUp()
    }

    @After
    fun tearDown() = h.tearDown()

    @Test
    fun aClosedTradeStaysInHistoryAsTheClockAdvances() = runBlocking {
        h.seed()
        val vm = h.buildViewModel()
        h.awaitUntil { vm.ready.value && vm.quote.value.bid > 0.0 }

        // Open a market long, then close it at the current bid.
        h.onMain { vm.placeOrder(OrderRequest(OrderType.MARKET, 0.10, side = Side.LONG)) }
        h.awaitUntil { h.live(1L).isNotEmpty() }
        val positionId = h.live(1L).single().id
        h.onMain { vm.closePosition(positionId) }
        h.awaitUntil { h.live(1L).isEmpty() && h.closed(1L).isNotEmpty() }
        assertEquals(1, h.closed(1L).size)

        // The replayed clock advances a few minutes past the close. The old history query
        // used lastVisibleCandleTs(lastProcessedTs) as its lower bound, so once that passed
        // the close time the trade dropped out of the list.
        repeat(5) { step ->
            h.db.seasonDao().updateLastProcessed(1L, h.openNow + (step + 1) * h.min)
            h.awaitUntil { true }
        }

        h.awaitUntil { vm.history.value.isNotEmpty() }
        assertEquals(
            "a closed trade must survive the clock advancing",
            1,
            vm.history.value.size,
        )
    }
}
