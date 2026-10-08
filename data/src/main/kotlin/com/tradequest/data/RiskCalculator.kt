package com.tradequest.data

import com.tradequest.engine.FillEngine
import com.tradequest.engine.Side

/**
 * Position-size maths for the order sheet.
 *
 * One lot is 100 ounces, so a $1 move on one lot is $100. Given the equity, the risk
 * fraction and the stop distance in price units, the safe size is
 * `equity * risk% / (stopDistance * 100)`.
 */
object RiskCalculator {

    const val OZ_PER_LOT = FillEngine.LOT_OZ
    const val MIN_LOTS = 0.01
    const val LOT_STEP = 0.01

    /** Lots that risk exactly [riskPercent] of [equity] over a [stopDistance] move. */
    fun lotsForRisk(equity: Double, riskPercent: Double, stopDistance: Double): Double {
        if (equity <= 0.0 || riskPercent <= 0.0 || stopDistance <= 0.0) return 0.0
        val riskAmount = equity * (riskPercent / 100.0)
        return roundLots(riskAmount / (stopDistance * OZ_PER_LOT))
    }

    /** Stop distance implied by an [entry] and a stop [price], or null when unset. */
    fun stopDistance(entry: Double?, stop: Double?): Double? {
        if (entry == null || stop == null) return null
        val d = kotlin.math.abs(entry - stop)
        return if (d <= 0.0) null else d
    }

    /** Money risked by [lots] over [stopDistance]. */
    fun riskAmount(lots: Double, stopDistance: Double): Double =
        lots * stopDistance * OZ_PER_LOT

    fun roundLots(lots: Double): Double = FillEngine.roundLots(lots).coerceAtLeast(0.0)

    /** Snap a raw lot value to the 0.01 grid. */
    fun snapLots(lots: Double): Double = roundLots(lots)

    /** A suggested stop distance for [side] at a fixed price offset. */
    fun defaultStop(entry: Double, side: Side, offset: Double): Double =
        if (side == Side.LONG) entry - offset else entry + offset
}
