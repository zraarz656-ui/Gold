package com.tradequest.app.ui

import com.tradequest.data.OrderRequest
import com.tradequest.data.OrderStatus
import com.tradequest.engine.OrderType
import com.tradequest.engine.Side
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * "Close ½" crashed the app. A partial close realises a subset of the lots and keeps the
 * position open with the remainder; the closed row and the remaining open row share the
 * same primary key in `trade_order` (position id), so the single insert in
 * [com.tradequest.data.TradingRepository.closePosition] collides and throws.
 */
@RunWith(RobolectricTestRunner::class)
class PartialCloseTest {

    private val h = TradingHarness()

    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        h.setUp()
    }

    @After
    fun tearDown() = h.tearDown()

    @Test
    fun halfCloseLeavesOnePartialClosedRowAndOneOpenPosition() {
        h.seed()
        val vm = h.buildViewModel()
        h.awaitUntil { vm.ready.value && vm.quote.value.bid > 0.0 }

        h.onMain { vm.placeOrder(OrderRequest(OrderType.MARKET, 0.10, side = Side.LONG)) }
        h.awaitUntil { h.live(1L).isNotEmpty() }
        val positionId = h.live(1L).single().id

        // "Close ½" from the Positions screen.
        h.onMain { vm.closePosition(positionId, 0.05) }

        h.awaitUntil { h.closed(1L).isNotEmpty() }
        val closed = h.closed(1L)
        val open = h.live(1L).filter { it.status == OrderStatus.OPEN }

        assertEquals("one partial closed row", 1, closed.size)
        assertEquals(0.05, closed.single().lots, 1e-9)
        assertEquals("the remainder stays open", 1, open.size)
        assertEquals(0.05, open.single().lots, 1e-9)

        // The closed row has its own positive id (never the position id, never negative) and
        // points back at the surviving position; the remainder keeps the original id.
        val row = closed.single()
        assertTrue("closed row id must be positive, was ${row.id}", row.id > 0L)
        assertTrue("closed row id must not reuse the position id", row.id != positionId)
        assertEquals("closed row links to its position", positionId, row.parentPositionId)
        assertEquals("the open remainder keeps the original id", positionId, open.single().id)
    }

    @Test
    fun aFullCloseReusesThePositionIdAndHasNoParent() {
        h.seed()
        val vm = h.buildViewModel()
        h.awaitUntil { vm.ready.value && vm.quote.value.bid > 0.0 }

        h.onMain { vm.placeOrder(OrderRequest(OrderType.MARKET, 0.10, side = Side.LONG)) }
        h.awaitUntil { h.live(1L).isNotEmpty() }
        val positionId = h.live(1L).single().id

        h.onMain { vm.closePosition(positionId, null) } // close everything

        h.awaitUntil { h.closed(1L).isNotEmpty() }
        val row = h.closed(1L).single()
        assertEquals(positionId, row.id)
        assertNull(row.parentPositionId)
        assertTrue("no position survives a full close", h.live(1L).none { it.status == OrderStatus.OPEN })
    }
}
