package com.tradequest.engine

/** How a season ended. */
enum class SeasonOutcome { ACTIVE, PASSED, FAILED }

/** Why a season ended, for the UI and notifications. */
enum class SeasonEndReason {
    /** Total profit reached +15% on at least [SeasonRules.MIN_TRADING_DAYS] distinct days. */
    PASSED,
    /** Equity fell [SeasonRules.MAX_DRAWDOWN_FRACTION] below its running peak. */
    MAX_DRAWDOWN,
    /** The daily-loss warning fired on [SeasonRules.MAX_DAILY_WARNINGS] separate days. */
    DAILY_WARNINGS,
    /** The replayed clock reached the last candle in the dataset. */
    DATA_END,
}

/** One of the three monthly stages. */
enum class SeasonStage { MONTH_1, MONTH_2, MONTH_3 }

/** A stage's +5% checkpoint state. */
data class StageResult(
    val stage: SeasonStage,
    val checkpointFraction: Double,
    val endTs: Long,
    val ended: Boolean = false,
    /** True when equity was at or above the checkpoint at the stage boundary. */
    val reached: Boolean = false,
)

/** Everything [SeasonRules] tracks across a season, updated candle by candle. */
data class SeasonState(
    val startedAt: Long,
    val startBalance: Double,
    val equity: Double = startBalance,
    val peakEquity: Double = startBalance,
    val maxDrawdownFraction: Double = 0.0,
    /** Distinct days on which at least one trade was placed or filled. */
    val distinctTradingDays: Int = 0,
    /** The FX day (17:00 NY boundary) the last candle belonged to. */
    val lastDayTs: Long = Long.MIN_VALUE,
    val dailyWarnings: Int = 0,
    /** True once the current day has already produced a warning or a counted trade. */
    val warnedToday: Boolean = false,
    val countedToday: Boolean = false,
)

/** The three stage checkpoints, tracked alongside [SeasonState]. */
data class StageTracking(
    val startBalance: Double,
    val stages: List<StageResult>,
) {
    val crossed: List<StageResult> get() = stages.filter { it.ended }

    companion object {
        fun initial(startTs: Long, startBalance: Double): StageTracking = StageTracking(
            startBalance = startBalance,
            stages = SeasonRules.STAGE_ENDS.mapIndexed { i, end ->
                StageResult(
                    stage = SeasonStage.entries[i],
                    checkpointFraction = SeasonRules.STAGE_PROFIT_FRACTION * (i + 1),
                    endTs = startTs + end,
                )
            },
        )
    }
}

/** One candle's worth of input for [SeasonRules.observe]. */
data class SeasonInput(
    /** Candle timestamp (the FX day it belongs to is derived from this). */
    val ts: Long,
    /** Account equity marked at the candle close. */
    val equity: Double,
    /** Equity at the start of the candle's FX day (the daily-loss baseline). */
    val dayStartEquity: Double,
    /** True when this candle placed or filled at least one trade. */
    val tradeActivity: Boolean = false,
    /** True when the engine itself flagged the daily loss limit on this candle. */
    val dailyLimitHit: Boolean = false,
    /** Last candle in the dataset; the season ends once [ts] reaches it. */
    val lastCandleTs: Long = Long.MIN_VALUE,
)

/** The result of folding one candle into the season. */
data class SeasonObservation(
    val state: SeasonState,
    val stages: StageTracking,
    /** Non-null exactly on the candle the season ends. */
    val decision: SeasonDecision? = null,
)

/** The pass/fail verdict and the candle it happened on. */
data class SeasonDecision(
    val outcome: SeasonOutcome,
    val reason: SeasonEndReason,
    val ts: Long,
    val equity: Double,
    val profitFraction: Double,
    val maxDrawdownFraction: Double,
    val distinctTradingDays: Int,
    val dailyWarnings: Int,
)

/**
 * The 12-week Gold Season challenge rules.
 *
 * ## Pass
 * Total profit at or above [PASS_PROFIT_FRACTION] (15% of the start balance) **and** trades
 * on at least [MIN_TRADING_DAYS] (20) distinct days. A day counts once any trade is placed
 * or filled on it.
 *
 * ## Fail
 * * Equity falls [MAX_DRAWDOWN_FRACTION] (10%) below its running peak, **or**
 * * the daily-loss warning fires on [MAX_DAILY_WARNINGS] (3) separate days. A day's warning
 *   fires once, when that day's equity loss reaches [DAILY_LOSS_FRACTION] (3%) of the day's
 *   start equity.
 *
 * ## End
 * The replayed clock reaching the last candle in the dataset ends the season in place (its
 * outcome is PASSED if the pass conditions already held, otherwise FAILED).
 *
 * The rules are evaluated at the **exact candle** a condition is met; [observe] returns the
 * [SeasonDecision] on that candle and no earlier. The rules are pure and independent of
 * [FillEngine].
 */
object SeasonRules {

    const val START_BALANCE: Double = 10_000.0
    const val PASS_PROFIT_FRACTION: Double = 0.15
    const val MIN_TRADING_DAYS: Int = 20
    const val MAX_DRAWDOWN_FRACTION: Double = 0.10
    const val MAX_DAILY_WARNINGS: Int = 3
    const val DAILY_LOSS_FRACTION: Double = 0.03
    const val STAGE_PROFIT_FRACTION: Double = 0.05
    const val STAGE_COUNT: Int = 3
    const val SEASON_DURATION_MS: Long = 12L * ClockEngine.WEEK_MS

    /** End of each stage, as an offset from the season start (one month each). */
    val STAGE_ENDS: List<Long> = listOf(
        4L * ClockEngine.WEEK_MS,
        8L * ClockEngine.WEEK_MS,
        12L * ClockEngine.WEEK_MS,
    )

    /** Begin tracking a fresh season. */
    fun initial(startTs: Long, startBalance: Double = START_BALANCE): Pair<SeasonState, StageTracking> {
        val state = SeasonState(
            startedAt = startTs,
            startBalance = startBalance,
            equity = startBalance,
            peakEquity = startBalance,
        )
        return state to StageTracking.initial(startTs, startBalance)
    }

    /**
     * Fold one candle into the season.
     *
     * @return the updated tracking plus the [SeasonDecision] when this candle ends the
     *   season, or null while it continues.
     */
    fun observe(
        state: SeasonState,
        stages: StageTracking,
        input: SeasonInput,
    ): SeasonObservation {
        val balance = state.startBalance
        var equity = state.equity
        var peak = state.peakEquity
        var maxDd = state.maxDrawdownFraction
        var warnings = state.dailyWarnings
        var warnedToday = state.warnedToday
        var countedToday = state.countedToday
        var lastDayTs = state.lastDayTs
        var days = state.distinctTradingDays

        val day = MarketCalendar.dayStart(input.ts)
        if (day != lastDayTs) {
            lastDayTs = day
            warnedToday = false
            countedToday = false
        }

        if (input.tradeActivity && !countedToday) {
            days += 1
            countedToday = true
        }

        // Daily-loss warning, once per day: either the engine flagged it or the day's
        // equity loss has reached 3% of the day's start equity.
        if (!warnedToday) {
            val limitEquity = input.dayStartEquity * (1.0 - DAILY_LOSS_FRACTION)
            if (input.dailyLimitHit || input.equity <= limitEquity) {
                warnings += 1
                warnedToday = true
            }
        }

        equity = input.equity
        peak = maxOf(peak, equity)
        val dd = if (peak > 0.0) ((peak - equity) / peak).coerceAtLeast(0.0) else 0.0
        maxDd = maxOf(maxDd, dd)

        val updatedState = state.copy(
            equity = equity,
            peakEquity = peak,
            maxDrawdownFraction = maxDd,
            distinctTradingDays = days,
            lastDayTs = lastDayTs,
            dailyWarnings = warnings,
            warnedToday = warnedToday,
            countedToday = countedToday,
        )

        // Close any stage whose boundary this candle has reached.
        val updatedStages = closeStages(stages, input.ts, equity)
        val profit = profitFraction(balance, equity)

        val decision = decide(
            state = updatedState,
            stages = updatedStages,
            ts = input.ts,
            equity = equity,
            profit = profit,
            lastCandleTs = input.lastCandleTs,
        )
        return SeasonObservation(updatedState, updatedStages, decision)
    }

    private fun closeStages(stages: StageTracking, ts: Long, equity: Double): StageTracking {
        val updated = stages.stages.map { stage ->
            if (!stage.ended && ts >= stage.endTs) {
                val checkpoint = stages.startBalance * (1.0 + stage.checkpointFraction)
                stage.copy(ended = true, reached = equity >= checkpoint)
            } else {
                stage
            }
        }
        return stages.copy(stages = updated)
    }

    private fun decide(
        state: SeasonState,
        stages: StageTracking,
        ts: Long,
        equity: Double,
        profit: Double,
        lastCandleTs: Long,
    ): SeasonDecision? {
        val base = { outcome: SeasonOutcome, reason: SeasonEndReason ->
            SeasonDecision(
                outcome = outcome,
                reason = reason,
                ts = ts,
                equity = equity,
                profitFraction = profit,
                maxDrawdownFraction = state.maxDrawdownFraction,
                distinctTradingDays = state.distinctTradingDays,
                dailyWarnings = state.dailyWarnings,
            )
        }

        if (state.maxDrawdownFraction >= MAX_DRAWDOWN_FRACTION) {
            return base(SeasonOutcome.FAILED, SeasonEndReason.MAX_DRAWDOWN)
        }
        if (state.dailyWarnings >= MAX_DAILY_WARNINGS) {
            return base(SeasonOutcome.FAILED, SeasonEndReason.DAILY_WARNINGS)
        }
        if (profit >= PASS_PROFIT_FRACTION && state.distinctTradingDays >= MIN_TRADING_DAYS) {
            return base(SeasonOutcome.PASSED, SeasonEndReason.PASSED)
        }
        if (lastCandleTs != Long.MIN_VALUE && ts >= lastCandleTs) {
            val passed = profit >= PASS_PROFIT_FRACTION && state.distinctTradingDays >= MIN_TRADING_DAYS
            return base(
                if (passed) SeasonOutcome.PASSED else SeasonOutcome.FAILED,
                SeasonEndReason.DATA_END,
            )
        }
        return null
    }

    /** Total profit as a fraction of the start balance. */
    fun profitFraction(startBalance: Double, equity: Double): Double =
        if (startBalance <= 0.0) 0.0 else equity / startBalance - 1.0
}
