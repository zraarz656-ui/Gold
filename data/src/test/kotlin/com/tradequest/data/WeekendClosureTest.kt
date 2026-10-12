package com.tradequest.data

import com.tradequest.engine.ClockEngine
import com.tradequest.engine.MarketCalendar
import com.tradequest.engine.OrderType
import com.tradequest.engine.Side
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Behaviour around the weekend closure: the replayed clock can sit inside it, no candle is
 * exposed there, yet open positions and pending orders survive and the clock can be moved
 * past the closure afterwards.
 */
@RunWith(RobolectricTestRunner::class)
class WeekendClosureTest {

    private val min = 60_000L

    // 2026-09-24 17:00 New York (21:00 UTC, Thursday) — the FX day open.
    private val friday = 1_790_283_600_000L

    // 2026-09-28 00:00 New York (04:00 UTC, Monday) — the week re-opens after the shut.
    private val monday = 1_790_568_000_000L

    // Saturday 12:00 UTC and Sunday 16:00 New York (20:00 UTC) — both inside the closure.
    private val saturdayNoon = 1_790_424_000_000L
    private val sundayLateNy = 1_790_539_200_000L

    /**
     * 12 contiguous hours from the Thursday open (ending at the Friday 17:00 NY weekend
     * bite), then the Monday session. No candle falls inside the closure.
     */
    private fun calendarRespecting(): Pair<List<Candle1m>, List<Candle1m>> =
        TestDb.candles(count = 720, startTs = friday) to
            TestDb.candles(count = 1440, startTs = monday)

    @Test
    fun theCalendarReportsTheClosure() {
        assertFalse(MarketCalendar.isClosed(friday))
        assertTrue(MarketCalendar.isClosed(saturdayNoon))
        assertTrue(MarketCalendar.isClosed(sundayLateNy))
        assertFalse(MarketCalendar.isClosed(monday))
    }

    @Test
    fun noCandleInsideTheClosureIsEverExposed() = runTest {
        val (fridaySession, mondaySession) = calendarRespecting()
        val db = TestDb.open()
        db.candleDao().insertAll(fridaySession + mondaySession)
        val repo = CandleRepository(db)

        // histNow sits on Saturday: the last visible candle is Friday's last one.
        val window = repo.upTo(saturdayNoon)
        assertTrue(window.all { it.ts <= saturdayNoon })
        assertFalse(window.any { MarketCalendar.isClosed(it.ts) })
        assertEquals(fridaySession.last().ts, window.last().ts)
        db.close()
    }

    @Test
    fun closureAdvanceReplaysNothingButKeepsOpenAndPendingOrders() = runTest {
        val (fridaySession, mondaySession) = calendarRespecting()
        val db = TestDb.open()
        db.candleDao().insertAll(fridaySession + mondaySession)
        val season = TestDb.seedSeason(db, friday)
        val trading = TradingRepository(db)

        // A market order for the position and a far limit order that stays pending.
        trading.place(season.id, OrderRequest(OrderType.MARKET, 0.1, side = Side.LONG), friday - min)
        trading.place(
            season.id,
            OrderRequest(OrderType.BUY_LIMIT, 0.1, price = 1000.0),
            friday - min,
        )
        // Replay the whole Friday session so the market order fills and nothing remains
        // before the weekend.
        val endOfFriday = fridaySession.last().ts + min
        CatchUpProcessor(db, batchSize = 200).run(season.id, endOfFriday)
        assertEquals(1, openCount(db, season.id))
        assertEquals(1, pendingCount(db, season.id))

        // Advancing over Saturday processes no candle and changes nothing.
        val during = CatchUpProcessor(db, batchSize = 50).run(season.id, saturdayNoon)
        assertEquals(0, during.processedCandles)
        assertEquals(1, openCount(db, season.id))
        assertEquals(1, pendingCount(db, season.id))
        db.close()
    }

    @Test
    fun timeTravelMovesPastTheClosure() = runTest {
        val (fridaySession, mondaySession) = calendarRespecting()
        val all = fridaySession + mondaySession
        val db = TestDb.open()
        db.candleDao().insertAll(all)

        // The replayed clock is a whole number of weeks behind a late real clock, landing
        // on Friday 00:00 — before the weekend closure.
        val realClock = 1_790_900_000_000L // 2026-09-30-ish, after the dataset
        val seasonId = db.seasonDao().insert(
            Season(
                startedAt = friday,
                offsetMs = realClock - friday,
                startBalance = 10_000.0,
                status = SeasonStatus.ACTIVE,
                lastProcessedTs = friday - min,
            ),
        )
        val repository = SeasonRepository(db) { realClock }

        // A hop of 4560 minutes lands exactly on the Sunday 17:00 New York re-open.
        val far = repository.timeTravel(seasonId, 4560)!!
        val lastVisible = ClockEngine.lastVisibleCandleTs(repository.histNow(far))
        assertFalse(MarketCalendar.isClosed(lastVisible))

        // A further large hop clamps to the dataset's last candle, never beyond it.
        val clamped = repository.timeTravel(seasonId, 100_000L)!!
        assertEquals(all.last().ts, ClockEngine.lastVisibleCandleTs(repository.histNow(clamped)))
        db.close()
    }

    private suspend fun openCount(db: TradeQuestDatabase, seasonId: Long): Int =
        db.tradeOrderDao().bySeason(seasonId).count { it.status == OrderStatus.OPEN }

    private suspend fun pendingCount(db: TradeQuestDatabase, seasonId: Long): Int =
        db.tradeOrderDao().bySeason(seasonId).count { it.status == OrderStatus.PENDING }
}
