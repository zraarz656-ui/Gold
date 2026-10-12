package com.tradequest.chart

import com.tradequest.engine.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pure rules for SL/TP/pending level drags: snapping, validation and tag text. */
class LevelRulesTest {

    private fun ctx(kind: OrderLineKind, side: Side, entry: Double = 2400.0, lots: Double = 1.0) =
        LevelContext(id = 7, kind = kind, side = side, entryPrice = entry, lots = lots)

    @Test
    fun `snap rounds to the 0_01 grid`() {
        assertEquals(2400.13, LevelRules.snap(2400.126), 1e-9)
        assertEquals(2400.12, LevelRules.snap(2400.124), 1e-9)
    }

    @Test
    fun `long SL must be below the entry`() {
        val c = ctx(OrderLineKind.SL, Side.LONG)
        val below = LevelRules.resolve(LevelEdit.Existing(c, 2390.0), market = 2400.0, spread = 0.3)
        assertTrue(below is LevelOutcome.Set)
        val inside = LevelRules.resolve(LevelEdit.Existing(c, 2410.0), market = 2400.0, spread = 0.3)
        assertTrue(inside is LevelOutcome.Rejected)
    }

    @Test
    fun `long TP must be above the entry`() {
        val c = ctx(OrderLineKind.TP, Side.LONG)
        assertTrue(LevelRules.resolve(LevelEdit.Existing(c, 2410.0), 2400.0, 0.3) is LevelOutcome.Set)
        assertTrue(LevelRules.resolve(LevelEdit.Existing(c, 2390.0), 2400.0, 0.3) is LevelOutcome.Rejected)
    }

    @Test
    fun `short side inverts the SL and TP rules`() {
        val sl = ctx(OrderLineKind.SL, Side.SHORT)
        assertTrue(LevelRules.resolve(LevelEdit.Existing(sl, 2410.0), 2400.0, 0.3) is LevelOutcome.Set)
        assertTrue(LevelRules.resolve(LevelEdit.Existing(sl, 2390.0), 2400.0, 0.3) is LevelOutcome.Rejected)
        val tp = ctx(OrderLineKind.TP, Side.SHORT)
        assertTrue(LevelRules.resolve(LevelEdit.Existing(tp, 2390.0), 2400.0, 0.3) is LevelOutcome.Set)
    }

    @Test
    fun `a level too close to the market is rejected`() {
        val c = ctx(OrderLineKind.TP, Side.LONG)
        // Market 2400, spread 0.30 -> minimum distance 0.40; 2400.20 is inside it.
        val out = LevelRules.resolve(LevelEdit.Existing(c, 2400.20), 2400.0, 0.30)
        assertTrue(out is LevelOutcome.Rejected)
    }

    @Test
    fun `dragging a level back onto the entry clears it`() {
        val c = ctx(OrderLineKind.SL, Side.LONG)
        val out = LevelRules.resolve(LevelEdit.Existing(c, 2400.01), 2400.0, 0.3)
        assertEquals(LevelOutcome.Clear(7, OrderLineKind.SL), out)
    }

    @Test
    fun `a new handle released on the entry cancels instead of clearing`() {
        val c = ctx(OrderLineKind.SL, Side.LONG)
        val out = LevelRules.resolve(LevelEdit.New(c, 2400.0), 2400.0, 0.3)
        assertEquals(LevelOutcome.Cancelled, out)
    }

    @Test
    fun `a new handle is flagged as new`() {
        val c = ctx(OrderLineKind.SL, Side.LONG)
        val out = LevelRules.resolve(LevelEdit.New(c, 2390.0), 2400.0, 0.3)
        assertEquals(LevelOutcome.Set(7, OrderLineKind.SL, 2390.0, isNew = true), out)
    }

    @Test
    fun `pending trigger may sit on either side but not on the market`() {
        val c = ctx(OrderLineKind.PENDING, Side.LONG, entry = 2400.0)
        assertTrue(LevelRules.resolve(LevelEdit.Existing(c, 2500.0), 2400.0, 0.3) is LevelOutcome.Set)
        assertTrue(LevelRules.resolve(LevelEdit.Existing(c, 2400.20), 2400.0, 0.30) is LevelOutcome.Rejected)
    }

    @Test
    fun `level pnl is signed by side and scaled by lots`() {
        val long = ctx(OrderLineKind.SL, Side.LONG, lots = 0.5)
        // 10 dollars below entry, 0.5 lots -> 10 * 100 oz * 0.5 = 500 loss.
        assertEquals(-500.0, LevelRules.levelPnl(long, 2390.0), 1e-6)
        val short = ctx(OrderLineKind.TP, Side.SHORT, lots = 0.5)
        assertEquals(500.0, LevelRules.levelPnl(short, 2390.0), 1e-6)
    }

    @Test
    fun `tag text names the price then the money at stake`() {
        val sl = ctx(OrderLineKind.SL, Side.LONG, lots = 1.0)
        val slTag = LevelRules.levelTag(OrderLineKind.SL, sl, 2390.0)
        assertTrue(slTag.startsWith("SL 2390.00"), slTag)
        assertTrue(slTag.contains("-$1000.00"), slTag)
        assertTrue(slTag.contains("%"), slTag)
        val tpTag = LevelRules.levelTag(OrderLineKind.TP, ctx(OrderLineKind.TP, Side.LONG), 2410.0)
        assertTrue(tpTag == "TP 2410.00  +$1000.00", tpTag)
    }
}
