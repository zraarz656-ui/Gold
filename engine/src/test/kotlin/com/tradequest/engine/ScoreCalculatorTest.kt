package com.tradequest.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Hand-calculated tests for [ScoreCalculator]. Every expected number below is derived by
 * hand from the formulas in the class documentation.
 */
class ScoreCalculatorTest {

    // ------------------------------------------------------------------ profit (40)

    @Test
    fun `profit pillar - full marks at the 15 percent target`() {
        // +15% => 40 * 15 / 15 = 40.
        assertEquals(40.0, ScoreCalculator.profitScore(10_000.0, 11_500.0), 1e-9)
    }

    @Test
    fun `profit pillar - proportional below the target`() {
        // +7.5% => 40 * 7.5 / 15 = 20.
        assertEquals(20.0, ScoreCalculator.profitScore(10_000.0, 10_750.0), 1e-9)
    }

    @Test
    fun `profit pillar - capped at 40 above the target`() {
        // +30% => 40 * 30 / 15 = 80, capped to 40.
        assertEquals(40.0, ScoreCalculator.profitScore(10_000.0, 13_000.0), 1e-9)
    }

    @Test
    fun `profit pillar - floored at 0 for a loss`() {
        // -10% => 40 * -10 / 15 = -26.67, floored to 0.
        assertEquals(0.0, ScoreCalculator.profitScore(10_000.0, 9_000.0), 1e-9)
    }

    // ------------------------------------------------------------------ risk (30)

    @Test
    fun `risk pillar - full marks at no drawdown`() {
        assertEquals(30.0, ScoreCalculator.riskScore(0.0), 1e-9)
    }

    @Test
    fun `risk pillar - half marks at 5 percent drawdown`() {
        // 30 * (1 - 5/10) = 15.
        assertEquals(15.0, ScoreCalculator.riskScore(0.05), 1e-9)
    }

    @Test
    fun `risk pillar - zero at the 10 percent limit`() {
        assertEquals(0.0, ScoreCalculator.riskScore(0.10), 1e-9)
    }

    @Test
    fun `risk pillar - clamped to 0 past the limit`() {
        assertEquals(0.0, ScoreCalculator.riskScore(0.20), 1e-9)
    }

    // ------------------------------------------------------------------ consistency (20)

    @Test
    fun `consistency - green day share`() {
        // 3 of 4 days green => 10 * 3/4 = 7.5.
        assertEquals(7.5, ScoreCalculator.greenDayScore(listOf(100.0, 50.0, -20.0, 30.0)), 1e-9)
    }

    @Test
    fun `consistency - green day share with no days is 0`() {
        assertEquals(0.0, ScoreCalculator.greenDayScore(emptyList()), 1e-9)
    }

    @Test
    fun `consistency - flat equity curve scores full smoothness`() {
        assertEquals(10.0, ScoreCalculator.smoothnessScore(listOf(10_000.0, 10_000.0, 10_000.0)), 1e-9)
    }

    @Test
    fun `consistency - bumpy curve scores zero smoothness`() {
        // Mean |step| = (|+3%| + |-3%|) / 2 = 3% = the reference => 10 * (1 - 1) = 0.
        assertEquals(
            0.0,
            ScoreCalculator.smoothnessScore(listOf(10_000.0, 10_300.0, 9_991.0)),
            1e-6,
        )
    }

    @Test
    fun `consistency - half bump scores half smoothness`() {
        // Steps: +1.5% then -1.5% => mean |step| = 1.5% => 10 * (1 - 1.5/3) = 5.
        assertEquals(
            5.0,
            ScoreCalculator.smoothnessScore(listOf(10_000.0, 10_150.0, 9_997.75)),
            1e-9,
        )
    }

    @Test
    fun `consistency - single point scores full smoothness`() {
        assertEquals(10.0, ScoreCalculator.smoothnessScore(listOf(10_000.0)), 1e-9)
    }

    @Test
    fun `consistency - combined green days and smoothness`() {
        // 3/4 green = 7.5; flat curve = 10 => 17.5.
        assertEquals(
            17.5,
            ScoreCalculator.consistencyScore(
                listOf(100.0, 50.0, -20.0, 30.0),
                listOf(10_000.0, 10_000.0, 10_000.0),
            ),
            1e-6,
        )
    }

    // ------------------------------------------------------------------ discipline (10)

    @Test
    fun `discipline - all three habits full marks`() {
        val trades = listOf(
            ScoredTrade(hasStopLoss = true, riskAmount = 100.0, equityAtEntry = 10_000.0, hasJournalEntry = true),
            ScoredTrade(hasStopLoss = true, riskAmount = 150.0, equityAtEntry = 10_000.0, hasJournalEntry = true),
        )
        assertEquals(10.0, ScoreCalculator.disciplineScore(trades), 1e-9)
    }

    @Test
    fun `discipline - one third each`() {
        // 2 trades: stops 1/2, low risk 1/2, journal 2/2 => 10/3 * (0.5 + 0.5 + 1.0) = 6.667.
        val trades = listOf(
            ScoredTrade(hasStopLoss = true, riskAmount = 100.0, equityAtEntry = 10_000.0, hasJournalEntry = true),
            ScoredTrade(hasStopLoss = false, riskAmount = 300.0, equityAtEntry = 10_000.0, hasJournalEntry = true),
        )
        assertEquals(20.0 / 3.0, ScoreCalculator.disciplineScore(trades), 1e-9)
    }

    @Test
    fun `discipline - 2 percent risk boundary is inclusive`() {
        // Exactly 2% of 10,000 = 200 counts as low risk; 200.01 does not.
        val atLimit = listOf(ScoredTrade(true, 200.0, 10_000.0, false))
        val overLimit = listOf(ScoredTrade(true, 200.01, 10_000.0, false))
        // stops 1/1 and journal 0 => (10/3) * (1 + 1 + 0) ... but at-limit low risk = 1.
        assertEquals(20.0 / 3.0, ScoreCalculator.disciplineScore(atLimit), 1e-9)
        assertEquals(10.0 / 3.0, ScoreCalculator.disciplineScore(overLimit), 1e-9)
    }

    @Test
    fun `discipline - no trades is 0`() {
        assertEquals(0.0, ScoreCalculator.disciplineScore(emptyList()), 1e-9)
    }

    // ------------------------------------------------------------------ totals & titles

    @Test
    fun `compute - end to end hand calculated`() {
        // Profit: +15% => 40. Risk: 0% dd => 30. Consistency: 3/4 green (7.5) + flat (10) = 17.5.
        // Discipline: 2 trades fully compliant => 10. Total = 97.5 => Gold Master.
        val inputs = ScoreInputs(
            startBalance = 10_000.0,
            finalEquity = 11_500.0,
            maxDrawdownFraction = 0.0,
            dailyPnl = listOf(100.0, 50.0, -20.0, 30.0),
            equityCurve = listOf(10_000.0, 10_000.0, 10_000.0),
            trades = listOf(
                ScoredTrade(true, 100.0, 10_000.0, true),
                ScoredTrade(true, 150.0, 10_000.0, true),
            ),
        )
        val (breakdown, title) = ScoreCalculator.scored(inputs)
        assertEquals(40.0, breakdown.profit, 1e-9)
        assertEquals(30.0, breakdown.risk, 1e-9)
        assertEquals(17.5, breakdown.consistency, 1e-6)
        assertEquals(10.0, breakdown.discipline, 1e-9)
        assertEquals(97.5, breakdown.total, 1e-6)
        assertEquals("Gold Master", title)
    }

    @Test
    fun `titles map to the documented bands`() {
        assertEquals("Rookie", ScoreCalculator.titleFor(0.0))
        assertEquals("Rookie", ScoreCalculator.titleFor(39.99))
        assertEquals("Trader", ScoreCalculator.titleFor(40.0))
        assertEquals("Trader", ScoreCalculator.titleFor(64.99))
        assertEquals("Pro", ScoreCalculator.titleFor(65.0))
        assertEquals("Pro", ScoreCalculator.titleFor(84.99))
        assertEquals("Gold Master", ScoreCalculator.titleFor(85.0))
        assertEquals("Gold Master", ScoreCalculator.titleFor(100.0))
    }

    @Test
    fun `a failing season scores low`() {
        // Failed by drawdown: -10% profit (0), 10% dd (0), no green days, no trades => 0.
        val inputs = ScoreInputs(
            startBalance = 10_000.0,
            finalEquity = 9_000.0,
            maxDrawdownFraction = 0.10,
            dailyPnl = listOf(-500.0, -500.0),
            equityCurve = listOf(10_000.0, 9_500.0, 9_000.0),
            trades = emptyList(),
        )
        val breakdown = ScoreCalculator.compute(inputs)
        assertEquals(0.0, breakdown.profit, 1e-9)
        assertEquals(0.0, breakdown.risk, 1e-9)
        assertEquals(0.0, breakdown.discipline, 1e-9)
        // Consistency: 0 green days (0) + smoothness. Steps: -5%, -5.26% => mean ~5.13% > 3% => 0.
        assertEquals(0.0, breakdown.consistency, 1e-6)
        assertEquals("Rookie", ScoreCalculator.titleFor(breakdown.total))
    }
}
