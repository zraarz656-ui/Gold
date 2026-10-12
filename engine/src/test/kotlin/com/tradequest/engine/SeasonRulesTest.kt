package com.tradequest.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Hand-calculated tests for the Gold Season pass/fail rules.
 *
 * All timestamps are built from a fixed January base (no DST), one candle per FX day,
 * so a day is unambiguous.
 */
class SeasonRulesTest {

    private val DAY = TestSupport.DAY

    /** 10:00 New York, no DST in January. */
    private fun dayTs(index: Int): Long = TestSupport.ny(2024, 1, 8, 10, 0) + index * DAY

    private val startTs = dayTs(0)

    /** Fold a run of inputs, returning the observation for each. */
    private fun fold(inputs: List<SeasonInput>): List<SeasonObservation> {
        val (s0, st0) = SeasonRules.initial(startTs)
        var state = s0
        var stages = st0
        return inputs.map { input ->
            val o = SeasonRules.observe(state, stages, input)
            state = o.state
            stages = o.stages
            o
        }
    }

    @Test
    fun `passes when profit is at least 15 percent on 20 distinct days`() {
        // 20 days trading +$500 each: equity 10,500 ... 20,000 (0 .. +100%), drawdown 0.
        val inputs = (1..20).map { d ->
            SeasonInput(
                ts = dayTs(d),
                equity = 10_000.0 + 500.0 * d,
                dayStartEquity = 10_000.0 + 500.0 * (d - 1),
                tradeActivity = true,
            )
        }
        val obs = fold(inputs)

        // Nothing ends before the 20th day.
        assertTrue(obs.take(19).all { it.decision == null }, "no decision before day 20")

        val decision = obs[19].decision
        assertNotNull(decision)
        assertEquals(SeasonOutcome.PASSED, decision!!.outcome)
        assertEquals(SeasonEndReason.PASSED, decision.reason)
        assertEquals(dayTs(20), decision.ts)
        assertEquals(20, decision.distinctTradingDays)
        assertEquals(20_000.0, decision.equity, 1e-9)
        assertEquals(1.0, decision.profitFraction, 1e-9)
    }

    @Test
    fun `does not pass early when profit hits 15 percent before 20 days`() {
        // Days 1..5 push equity to 12,000 (+20%) but only 5 trading days count.
        val inputs = (1..5).map { d ->
            SeasonInput(
                ts = dayTs(d),
                equity = 10_000.0 + 400.0 * d,
                dayStartEquity = 10_000.0 + 400.0 * (d - 1),
                tradeActivity = true,
            )
        }
        val obs = fold(inputs)
        assertTrue(obs.all { it.decision == null }, "profit alone must not pass before 20 days")
        assertEquals(5, obs.last().state.distinctTradingDays)
    }

    @Test
    fun `fails when equity falls 10 percent below its peak`() {
        val inputs = listOf(
            SeasonInput(ts = dayTs(1), equity = 10_000.0, dayStartEquity = 10_000.0, tradeActivity = true),
            SeasonInput(ts = dayTs(2), equity = 12_000.0, dayStartEquity = 10_000.0, tradeActivity = true),
            // 12,000 * 0.9 = 10,800 exactly => 10% drawdown.
            SeasonInput(ts = dayTs(3), equity = 10_800.0, dayStartEquity = 12_000.0, tradeActivity = true),
        )
        val obs = fold(inputs)
        assertNull(obs[0].decision)
        assertNull(obs[1].decision)
        val decision = obs[2].decision
        assertNotNull(decision)
        assertEquals(SeasonOutcome.FAILED, decision!!.outcome)
        assertEquals(SeasonEndReason.MAX_DRAWDOWN, decision.reason)
        assertEquals(dayTs(3), decision.ts)
        assertEquals(0.10, decision.maxDrawdownFraction, 1e-9)
    }

    @Test
    fun `9 point 9 percent drawdown does not fail, 10 percent does`() {
        val below = fold(
            listOf(
                SeasonInput(ts = dayTs(1), equity = 10_000.0, dayStartEquity = 10_000.0),
                SeasonInput(ts = dayTs(2), equity = 9_010.0, dayStartEquity = 10_000.0),
            ),
        )
        assertNull(below.last().decision, "9.9% drawdown must not fail")
        assertEquals(0.099, below.last().state.maxDrawdownFraction, 1e-9)

        val atLimit = fold(
            listOf(
                SeasonInput(ts = dayTs(1), equity = 10_000.0, dayStartEquity = 10_000.0),
                SeasonInput(ts = dayTs(2), equity = 9_000.0, dayStartEquity = 10_000.0),
            ),
        )
        assertEquals(SeasonOutcome.FAILED, atLimit.last().decision!!.outcome)
        assertEquals(SeasonEndReason.MAX_DRAWDOWN, atLimit.last().decision!!.reason)
    }

    @Test
    fun `fails after three daily warnings`() {
        // Each day loses 3% of its own start equity; cumulative drawdown stays under 10%.
        val inputs = listOf(
            SeasonInput(ts = dayTs(1), equity = 9_700.0, dayStartEquity = 10_000.0, tradeActivity = true),
            SeasonInput(ts = dayTs(2), equity = 9_409.0, dayStartEquity = 9_700.0, tradeActivity = true),
            SeasonInput(ts = dayTs(3), equity = 9_126.73, dayStartEquity = 9_409.0, tradeActivity = true),
        )
        val obs = fold(inputs)
        assertNull(obs[0].decision, "one warning is not a fail")
        assertNull(obs[1].decision, "two warnings are not a fail")
        assertEquals(1, obs[0].state.dailyWarnings)
        assertEquals(2, obs[1].state.dailyWarnings)

        val decision = obs[2].decision
        assertNotNull(decision)
        assertEquals(SeasonOutcome.FAILED, decision!!.outcome)
        assertEquals(SeasonEndReason.DAILY_WARNINGS, decision.reason)
        assertEquals(3, decision.dailyWarnings)
        assertEquals(dayTs(3), decision.ts)
    }

    @Test
    fun `a warning fires at most once per day`() {
        // Three losing candles in the SAME day must count as a single warning.
        val obs = fold(
            listOf(
                SeasonInput(ts = dayTs(1), equity = 9_700.0, dayStartEquity = 10_000.0, tradeActivity = true),
                SeasonInput(ts = dayTs(1) + 60_000L, equity = 9_500.0, dayStartEquity = 10_000.0),
                SeasonInput(ts = dayTs(1) + 120_000L, equity = 9_300.0, dayStartEquity = 10_000.0),
            ),
        )
        assertEquals(1, obs.last().state.dailyWarnings)
        assertNull(obs.last().decision)
    }

    @Test
    fun `drawdown failure takes precedence over a simultaneous pass`() {
        // Peak 13,000 then 11,500: +15% profit AND 11.5% drawdown on the same candle.
        val inputs = listOf(
            SeasonInput(ts = dayTs(1), equity = 13_000.0, dayStartEquity = 10_000.0, tradeActivity = true),
            SeasonInput(ts = dayTs(2), equity = 11_500.0, dayStartEquity = 13_000.0, tradeActivity = true),
        )
        val decision = fold(inputs).last().decision
        assertNotNull(decision)
        assertEquals(SeasonOutcome.FAILED, decision!!.outcome)
        assertEquals(SeasonEndReason.MAX_DRAWDOWN, decision.reason)
    }

    @Test
    fun `the 20 day minimum is exactly 20`() {
        // 19 days with +20% profit: no pass. The 20th day (still above +15%): pass.
        val inputs = (1..20).map { d ->
            SeasonInput(
                ts = dayTs(d),
                equity = 12_000.0,
                dayStartEquity = 12_000.0,
                tradeActivity = true,
            )
        }
        val obs = fold(inputs)
        assertTrue(obs.take(19).all { it.decision == null }, "19 days is not enough")
        assertEquals(SeasonOutcome.PASSED, obs[19].decision!!.outcome)
        assertEquals(20, obs[19].decision!!.distinctTradingDays)
    }

    @Test
    fun `stage checkpoints are evaluated at their boundaries`() {
        val end1 = startTs + 4L * ClockEngine.WEEK_MS
        val end2 = startTs + 8L * ClockEngine.WEEK_MS
        val end3 = startTs + 12L * ClockEngine.WEEK_MS
        val obs = fold(
            listOf(
                // Stage 1: +6% >= +5% checkpoint.
                SeasonInput(ts = end1, equity = 10_600.0, dayStartEquity = 10_000.0),
                // Stage 2: +4% < +10% checkpoint.
                SeasonInput(ts = end2, equity = 10_400.0, dayStartEquity = 10_600.0),
                // Stage 3: +16% >= +15% checkpoint.
                SeasonInput(ts = end3, equity = 11_600.0, dayStartEquity = 10_400.0),
            ),
        )
        val stages = obs.last().stages.stages
        assertEquals(3, stages.size)
        assertTrue(stages[0].ended)
        assertTrue(stages[0].reached, "stage 1 reached at +6%")
        assertEquals(0.05, stages[0].checkpointFraction, 1e-9)
        assertTrue(stages[1].ended)
        assertTrue(!stages[1].reached, "stage 2 missed at +4%")
        assertEquals(0.10, stages[1].checkpointFraction, 1e-9)
        assertTrue(stages[2].ended)
        assertTrue(stages[2].reached, "stage 3 reached at +16%")
        assertEquals(0.15, stages[2].checkpointFraction, 1e-9)
    }

    @Test
    fun `stage is not ended before its boundary`() {
        val obs = fold(
            listOf(SeasonInput(ts = startTs + 4L * ClockEngine.WEEK_MS - 60_000L, equity = 12_000.0, dayStartEquity = 10_000.0)),
        )
        assertTrue(obs.last().stages.stages.none { it.ended }, "no stage ends before its boundary")
    }

    @Test
    fun `season ends at the last candle - passed`() {
        val last = dayTs(30)
        // Profit +20% and 20 trading days, then the dataset runs out.
        val inputs = (1..20).map { d ->
            SeasonInput(ts = dayTs(d), equity = 12_000.0, dayStartEquity = 12_000.0, tradeActivity = true)
        } + SeasonInput(ts = last, equity = 12_000.0, dayStartEquity = 12_000.0, lastCandleTs = last)

        val obs = fold(inputs)
        // The pass already fired on day 20; the last-candle rule is exercised separately below.
        assertEquals(SeasonOutcome.PASSED, obs[19].decision!!.outcome)
        assertEquals(SeasonEndReason.PASSED, obs[19].decision!!.reason)
    }

    @Test
    fun `season ends at the last candle - failed when the pass conditions were not met`() {
        val last = dayTs(30)
        val inputs = listOf(
            SeasonInput(ts = dayTs(1), equity = 10_200.0, dayStartEquity = 10_000.0, tradeActivity = true),
            SeasonInput(ts = last, equity = 10_200.0, dayStartEquity = 10_200.0, lastCandleTs = last),
        )
        val decision = fold(inputs).last().decision
        assertNotNull(decision)
        assertEquals(SeasonOutcome.FAILED, decision!!.outcome)
        assertEquals(SeasonEndReason.DATA_END, decision.reason)
        assertEquals(last, decision.ts)
    }

    @Test
    fun `profitFraction is relative to the start balance`() {
        assertEquals(0.15, SeasonRules.profitFraction(10_000.0, 11_500.0), 1e-9)
        assertEquals(-0.10, SeasonRules.profitFraction(10_000.0, 9_000.0), 1e-9)
        assertEquals(0.0, SeasonRules.profitFraction(0.0, 9_000.0), 1e-9)
    }
}
