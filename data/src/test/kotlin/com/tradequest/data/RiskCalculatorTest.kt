package com.tradequest.data

import com.tradequest.engine.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure position-sizing maths, no Android needed. */
class RiskCalculatorTest {

    @Test
    fun lotsForRiskUsesHundredOuncesPerLot() {
        // $10,000 equity, 1% risk = $100, stop 5.00 away -> 100 / (5 * 100) = 0.2 lots
        assertEquals(0.2, RiskCalculator.lotsForRisk(10_000.0, 1.0, 5.0), 1e-9)
    }

    @Test
    fun lotsForRiskIsZeroOnBadInput() {
        assertEquals(0.0, RiskCalculator.lotsForRisk(0.0, 1.0, 5.0), 1e-9)
        assertEquals(0.0, RiskCalculator.lotsForRisk(10_000.0, 0.0, 5.0), 1e-9)
        assertEquals(0.0, RiskCalculator.lotsForRisk(10_000.0, 1.0, 0.0), 1e-9)
    }

    @Test
    fun stopDistanceIsAbsolute() {
        assertEquals(5.0, RiskCalculator.stopDistance(2400.0, 2395.0)!!, 1e-9)
        assertEquals(5.0, RiskCalculator.stopDistance(2395.0, 2400.0)!!, 1e-9)
        assertNull(RiskCalculator.stopDistance(null, 2395.0))
        assertNull(RiskCalculator.stopDistance(2400.0, null))
    }

    @Test
    fun riskAmountRoundTrips() {
        assertEquals(100.0, RiskCalculator.riskAmount(0.2, 5.0), 1e-9)
    }

    @Test
    fun lotsSnapToTheCentGrid() {
        assertEquals(0.13, RiskCalculator.snapLots(0.1267), 1e-9)
        assertEquals(0.0, RiskCalculator.snapLots(0.004), 1e-9)
    }

    @Test
    fun defaultStopIsBelowForLongsAndAboveForShorts() {
        assertEquals(2395.0, RiskCalculator.defaultStop(2400.0, Side.LONG, 5.0), 1e-9)
        assertEquals(2405.0, RiskCalculator.defaultStop(2400.0, Side.SHORT, 5.0), 1e-9)
    }
}
