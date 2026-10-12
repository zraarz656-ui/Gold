package com.tradequest.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tradequest.engine.Impact
import com.tradequest.engine.OrderType
import com.tradequest.engine.Side
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** In-memory Room tests for every DAO. */
@RunWith(RobolectricTestRunner::class)
class DaoTest {

    private lateinit var db: TradeQuestDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TradeQuestDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun candleDao_rangeAndUpTo() = runTest {
        db.candleDao().insertAll(
            listOf(candle(1000, 1.0), candle(2000, 2.0), candle(3000, 3.0), candle(4000, 4.0)),
        )
        assertEquals(4, db.candleDao().count())
        assertEquals(1000L, db.candleDao().minTs())
        assertEquals(4000L, db.candleDao().maxTs())
        assertEquals(listOf(2000L, 3000L), db.candleDao().range(2000, 4000).map { it.ts })
        assertEquals(listOf(1000L, 2000L, 3000L), db.candleDao().upTo(3000).map { it.ts })
        assertEquals(listOf(4000L, 3000L), db.candleDao().lastBefore(4000, 2).map { it.ts })
    }

    @Test
    fun candleDao_ignoresDuplicatePrimaryKeys() = runTest {
        db.candleDao().insertAll(listOf(candle(1000, 1.0)))
        db.candleDao().insertAll(listOf(candle(1000, 9.0)))
        assertEquals(1, db.candleDao().count())
        assertEquals(1.0, db.candleDao().upTo(1000).first().o, 1e-9)
    }

    @Test
    fun newsDao_roundTrip() = runTest {
        db.newsDao().insertAll(
            listOf(
                NewsEntity(ts = 1000, title = "NFP", impact = Impact.HIGH),
                NewsEntity(ts = 5000, title = "CPI", impact = Impact.MEDIUM),
            ),
        )
        assertEquals(2, db.newsDao().count())
        assertEquals("NFP", db.newsDao().all().first().title)
        assertEquals(listOf("NFP"), db.newsDao().between(0, 2000).map { it.title })
    }

    @Test
    fun seasonDao_activeAndCheckpoint() = runTest {
        val id = db.seasonDao().insert(
            Season(startedAt = 1, offsetMs = 0, startBalance = 10_000.0, status = SeasonStatus.ACTIVE, lastProcessedTs = 42),
        )
        val active = db.seasonDao().active()
        assertNotNull(active)
        assertEquals(10_000.0, active!!.startBalance, 1e-9)
        db.seasonDao().updateLastProcessed(id, 99)
        assertEquals(99L, db.seasonDao().byId(id)!!.lastProcessedTs)
        db.seasonDao().end(id, SeasonStatus.ENDED, 123, 1.5)
        assertNull(db.seasonDao().active())
        assertEquals(SeasonStatus.ENDED, db.seasonDao().byId(id)!!.status)
    }

    @Test
    fun tradeOrderDao_liveAndClosed() = runTest {
        db.tradeOrderDao().insertAll(
            listOf(
                order(1, OrderStatus.PENDING, OrderType.BUY_LIMIT, Side.LONG),
                order(2, OrderStatus.OPEN, OrderType.MARKET, Side.SHORT),
                order(3, OrderStatus.CLOSED, OrderType.MARKET, Side.LONG),
            ),
        )
        assertEquals(listOf(1L, 2L), db.tradeOrderDao().live(1).map { it.id })
        assertEquals(listOf(3L), db.tradeOrderDao().closed(1).map { it.id })
        db.tradeOrderDao().deleteLive(1)
        assertEquals(listOf(3L), db.tradeOrderDao().bySeason(1).map { it.id })
    }

    @Test
    fun dailyStats_upsertReplaces() = runTest {
        db.dailyStatsDao().upsert(DailyStats(1, 100, 10_000.0, 0.0, false))
        db.dailyStatsDao().upsert(DailyStats(1, 100, 10_000.0, -50.0, true))
        val row = db.dailyStatsDao().forDay(1, 100)!!
        assertEquals(-50.0, row.pnl, 1e-9)
        assertEquals(true, row.limitHit)
        assertEquals(1, db.dailyStatsDao().bySeason(1).size)
    }

    @Test
    fun equitySnapshot_latest() = runTest {
        db.equitySnapshotDao().insertAll(
            listOf(
                EquitySnapshotEntity(1, 1000, 10_000.0, 10_000.0, 0.0),
                EquitySnapshotEntity(1, 2000, 10_050.0, 10_000.0, 100.0),
            ),
        )
        assertEquals(2000L, db.equitySnapshotDao().latest(1)!!.ts)
        assertEquals(2, db.equitySnapshotDao().bySeason(1).size)
    }

    @Test
    fun settings_putAndGet() = runTest {
        db.settingsDao().put(SettingEntity("k", "v1"))
        db.settingsDao().put(SettingEntity("k", "v2"))
        assertEquals("v2", db.settingsDao().get("k"))
        assertNull(db.settingsDao().get("missing"))
    }

    private fun candle(ts: Long, price: Double) =
        Candle1m(ts, price, price + 1, price - 1, price, 100.0)

    private fun order(id: Long, status: OrderStatus, type: OrderType, side: Side) = TradeOrder(
        id = id, seasonId = 1, type = type, side = side, lots = 0.1, entryPrice = 2400.0,
        sl = null, tp = null, trailingDist = null, status = status, openedAt = 1,
        closedAt = null, closePrice = null, pnl = null, fees = null, tag = null, note = null,
    )
}
