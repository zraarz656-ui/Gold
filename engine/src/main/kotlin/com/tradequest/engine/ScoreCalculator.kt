package com.tradequest.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The four score pillars, each with its maximum weight. */
data class ScoreBreakdown(
    val profit: Double,
    val risk: Double,
    val consistency: Double,
    val discipline: Double,
) {
    val total: Double get() = profit + risk + consistency + discipline
}

/** One closed trade, as needed for the discipline pillar. */
data class ScoredTrade(
    /** True when the trade carried a stop-loss from entry. */
    val hasStopLoss: Boolean,
    /** Money risked if the stop were hit (|entry - stop| * 100 * lots), or null when no stop. */
    val riskAmount: Double?,
    /** Equity when the trade was opened; used for the 2% risk test. */
    val equityAtEntry: Double,
    /** True when the trade has a filled-in journal entry (tag or note). */
    val hasJournalEntry: Boolean,
)

/** Everything [ScoreCalculator] needs to grade a finished season. */
data class ScoreInputs(
    val startBalance: Double,
    val finalEquity: Double,
    val maxDrawdownFraction: Double,
    /** Per-day realised P&L for every day the season traded; one entry per trading day. */
    val dailyPnl: List<Double>,
    /** Equity samples in time order, used for the smoothness term. */
    val equityCurve: List<Double>,
    val trades: List<ScoredTrade>,
)

/**
 * Grades a finished season out of 100, split across four pillars:
 *
 * * **Profit (40).** `min(40, 40 * profit% / 15)`, i.e. full marks at the +15% pass target.
 * * **Risk (30).** `clamp(30 * (1 - maxDrawdown% / 10), 0, 30)`, i.e. full marks at no
 *   drawdown and zero at the 10% fail threshold.
 * * **Consistency (20).** 10 for the share of winning days, plus 10 for the smoothness of
 *   the equity curve. Smoothness uses the mean absolute step of the curve's step-to-step
 *   *returns*, squashed so a bumpy curve scores low:
 *   `smoothness = 10 * clamp(1 - meanAbsReturn / (SMOOTHNESS_REFERENCE * 10), 0, 1)`,
 *   where the reference is the 3% daily-loss level (`DAILY_LOSS_FRACTION`). A curve that
 *   moves 3% per step on average scores zero here; a flat curve scores 10. With a single
 *   point (no steps) it scores the full 10.
 * * **Discipline (10).** An equal split (each 1/3, so 3.33 points) of the share of trades
 *   with a stop-loss, the share risking at most 2% of equity at entry, and the share with a
 *   filled-in journal entry. With no trades the discipline pillar is 0.
 *
 * Titles: [titleFor] maps the total to Rookie (<40), Trader (40-64), Pro (65-84) and
 * Gold Master (85+).
 */
object ScoreCalculator {

    const val MAX_PROFIT = 40.0
    const val MAX_RISK = 30.0
    const val MAX_CONSISTENCY = 20.0
    const val MAX_DISCIPLINE = 10.0

    /** Profit % that earns the full 40 points (the pass target). */
    const val PROFIT_TARGET_PERCENT = SeasonRules.PASS_PROFIT_FRACTION * 100.0

    /** Drawdown % that zeroes the risk pillar (the fail threshold). */
    const val DRAWDOWN_LIMIT_PERCENT = SeasonRules.MAX_DRAWDOWN_FRACTION * 100.0

    /** Per-trade risk ceiling, as a percentage of equity at entry. */
    const val MAX_TRADE_RISK_PERCENT = 2.0

    /** Mean absolute equity-curve step return that zeroes the smoothness half. */
    const val SMOOTHNESS_REFERENCE = SeasonRules.DAILY_LOSS_FRACTION

    /** Grade a finished season. */
    fun compute(inputs: ScoreInputs): ScoreBreakdown = ScoreBreakdown(
        profit = profitScore(inputs.startBalance, inputs.finalEquity),
        risk = riskScore(inputs.maxDrawdownFraction),
        consistency = consistencyScore(inputs.dailyPnl, inputs.equityCurve),
        discipline = disciplineScore(inputs.trades),
    )

    /** `clamp(40 * profit% / 15, 0, 40)`. */
    fun profitScore(startBalance: Double, finalEquity: Double): Double {
        val profitPercent = SeasonRules.profitFraction(startBalance, finalEquity) * 100.0
        return (MAX_PROFIT * profitPercent / PROFIT_TARGET_PERCENT).coerceIn(0.0, MAX_PROFIT)
    }

    /** `clamp(30 * (1 - maxDrawdown% / 10), 0, 30)`. */
    fun riskScore(maxDrawdownFraction: Double): Double {
        val percent = maxDrawdownFraction * 100.0
        return (MAX_RISK * (1.0 - percent / DRAWDOWN_LIMIT_PERCENT)).coerceIn(0.0, MAX_RISK)
    }

    /** Share of green days (10) plus equity-curve smoothness (10). */
    fun consistencyScore(dailyPnl: List<Double>, equityCurve: List<Double>): Double =
        greenDayScore(dailyPnl) + smoothnessScore(equityCurve)

    /** `10 * greenDays / totalDays`, or 0 with no days. */
    fun greenDayScore(dailyPnl: List<Double>): Double {
        if (dailyPnl.isEmpty()) return 0.0
        val green = dailyPnl.count { it > 0.0 }
        return MAX_CONSISTENCY / 2.0 * green / dailyPnl.size
    }

    /**
     * Smoothness of the equity curve: 10 for a flat curve, 0 when the mean absolute
     * step return reaches [SMOOTHNESS_REFERENCE] (3%). A curve with fewer than two points
     * scores the full 10.
     */
    fun smoothnessScore(equityCurve: List<Double>): Double {
        if (equityCurve.size < 2) return MAX_CONSISTENCY / 2.0
        var sum = 0.0
        var steps = 0
        for (i in 1 until equityCurve.size) {
            val prev = equityCurve[i - 1]
            if (prev <= 0.0) continue
            sum += abs(equityCurve[i] / prev - 1.0)
            steps += 1
        }
        if (steps == 0) return MAX_CONSISTENCY / 2.0
        val meanAbs = sum / steps
        return MAX_CONSISTENCY / 2.0 * (1.0 - meanAbs / SMOOTHNESS_REFERENCE).coerceIn(0.0, 1.0)
    }

    /** Stop-loss share, low-risk share and journal share, each worth a third of 10. */
    fun disciplineScore(trades: List<ScoredTrade>): Double {
        if (trades.isEmpty()) return 0.0
        val n = trades.size.toDouble()
        val withStop = trades.count { it.hasStopLoss }.toDouble()
        val lowRisk = trades.count { it.riskWithin(MAX_TRADE_RISK_PERCENT) }.toDouble()
        val journalled = trades.count { it.hasJournalEntry }.toDouble()
        val third = MAX_DISCIPLINE / 3.0
        return third * (withStop / n) + third * (lowRisk / n) + third * (journalled / n)
    }

    private fun ScoredTrade.riskWithin(percent: Double): Boolean {
        val risk = riskAmount ?: return false
        if (equityAtEntry <= 0.0) return false
        return risk <= equityAtEntry * (percent / 100.0)
    }

    /** Map a total score to its title. */
    fun titleFor(total: Double): String = when {
        total < 40.0 -> "Rookie"
        total < 65.0 -> "Trader"
        total < 85.0 -> "Pro"
        else -> "Gold Master"
    }

    /** Convenience: grade and title in one call. */
    fun scored(inputs: ScoreInputs): Pair<ScoreBreakdown, String> {
        val breakdown = compute(inputs)
        return breakdown to titleFor(breakdown.total)
    }

    /** Money risked by a position, or null when it carries no stop. */
    fun riskAmount(entry: Double, stop: Double?, lots: Double): Double? {
        if (stop == null) return null
        return max(0.0, abs(entry - stop)) * FillEngine.LOT_OZ * lots
    }
}
