package com.tradequest.data

import com.tradequest.engine.Impact
import com.tradequest.engine.OrderType
import com.tradequest.engine.Side
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A forced data re-import must rewrite candle_1m and news_event and nothing else. These
 * tests plant a full season's worth of state, re-import, and check every user-owned table
 * survives byte-for-byte while the data tables are replaced.
 */
@RunWith(RobolectricTestRunner::class)
class ReimportIsolationTest {

    private val plausibleDir = File("src/test/resources/plausible_assets")
    private val min = 60_000L
    private val base = 1_700_000_000_000L // mid-week Tuesday

    /** Every table that is NOT imported data, plus the settings that back the season. */
    private suspend fun plantUserState(db: TradeQuestDatabase) {
        val seasonId = db.seasonDao().insert(
            Season(
                startedAt = base,
                offsetMs = 3 * 604_800_000L,
                startBalance = 10_000.0,
                status = SeasonStatus.ACTIVE,
                lastProcessedTs = base + 500 * min,
                score = 4.2,
            ),
        )
        db.tradeOrderDao().insertAll(
            listOf(
                order(seasonId, base, OrderStatus.OPEN, pnl = null),
                order(seasonId, base + min, OrderStatus.PENDING, pnl = null),
                order(seasonId, base + 2 * min, OrderStatus.CLOSED, closePrice = 2401.0, pnl = 12.5),
            ),
        )
        db.dailyStatsDao().upsert(DailyStats(seasonId, dayKey = base, startEquity = 10_000.0, pnl = -25.0, limitHit = false))
        db.equitySnapshotDao().insertAll(
            listOf(EquitySnapshotEntity(seasonId, base, 10_000.0, 10_000.0, 0.0)),
        )
        db.settingsDao().put(SettingEntity(AccountCheckpoint.KEY, "{\"balance\":10000.0}"))
    }

    private fun order(
        seasonId: Long,
        ts: Long,
        status: OrderStatus,
        closePrice: Double? = null,
        pnl: Double? = null,
    ) = TradeOrder(
        seasonId = seasonId,
        type = OrderType.BUY_LIMIT,
        side = Side.LONG,
        lots = 0.10,
        entryPrice = 2400.0,
        sl = 2395.0,
        tp = 2410.0,
        trailingDist = null,
        status = status,
        openedAt = ts,
        closedAt = if (status == OrderStatus.CLOSED) ts + min else null,
        closePrice = closePrice,
        pnl = pnl,
        fees = -0.5,
        tag = "planted",
        note = "must survive re-import",
    )

    @Test
    fun forcedReimportReplacesOnlyCandlesAndNews() = runTest {
        val db = TestDb.open()
        plantUserState(db)

        // A prior import with recognisably different candle range and news.
        db.candleDao().insertAll(TestDb.candles(count = 7, startTs = base))
        db.newsDao().insertAll(listOf(NewsEntity(ts = base, title = "OLD NEWS", impact = Impact.LOW)))

        // Snapshot every non-data table before the re-import.
        val seasonBefore = db.seasonDao().active()
        val tradesBefore = db.tradeOrderDao().bySeason(seasonBefore!!.id)
        val statsBefore = db.dailyStatsDao().bySeason(seasonBefore.id)
        val equityBefore = db.equitySnapshotDao().bySeason(seasonBefore.id)
        val checkpointBefore = db.settingsDao().get(AccountCheckpoint.KEY)

        DatasetImporter.import(db, FileAssetSource(plausibleDir), force = true, minRows = 1)

        // Data tables were replaced by the asset's 4 candles / 3 news rows.
        assertEquals(10L, db.candleDao().count())
        assertEquals(3L, db.newsDao().count())

        // Every user-owned table is untouched. The season row survives too; only its
        // needsReset flag may flip because the candle range changed (proved separately).
        val seasonAfter = db.seasonDao().active()!!
        assertEquals(seasonBefore.id, seasonAfter.id)
        assertEquals(seasonBefore.startedAt, seasonAfter.startedAt)
        assertEquals(seasonBefore.offsetMs, seasonAfter.offsetMs)
        assertEquals(seasonBefore.startBalance, seasonAfter.startBalance, 0.0)
        assertEquals(seasonBefore.status, seasonAfter.status)
        assertEquals(seasonBefore.lastProcessedTs, seasonAfter.lastProcessedTs)
        assertEquals(seasonBefore.score, seasonAfter.score, 0.0)
        assertEquals(tradesBefore, db.tradeOrderDao().bySeason(seasonBefore.id))
        assertEquals(statsBefore, db.dailyStatsDao().bySeason(seasonBefore.id))
        assertEquals(equityBefore, db.equitySnapshotDao().bySeason(seasonBefore.id))
        assertEquals(checkpointBefore, db.settingsDao().get(AccountCheckpoint.KEY))
        db.close()
    }

    @Test
    fun unchangedRangeDoesNotFlagTheSeason() = runTest {
        val db = TestDb.open()
        val season = TestDb.seedSeason(db, base)
        // Import once, then force re-import the same asset: the range is identical.
        DatasetImporter.import(db, FileAssetSource(plausibleDir), force = true, minRows = 1)
        val result = DatasetImporter.import(db, FileAssetSource(plausibleDir), force = true, minRows = 1)

        assertFalse(result.rangeChanged)
        assertFalse(db.seasonDao().byId(season.id)!!.needsReset)
        db.close()
    }

    @Test
    fun changedRangeFlagsTheSeasonForReset() = runTest {
        val db = TestDb.open()
        val season = TestDb.seedSeason(db, base)
        db.candleDao().insertAll(TestDb.candles(count = 7, startTs = base)) // a different range
        db.seasonDao().clearNeedsReset(season.id)

        val result = DatasetImporter.import(db, FileAssetSource(plausibleDir), force = true, minRows = 1)

        assertTrue(result.rangeChanged)
        assertTrue(db.seasonDao().byId(season.id)!!.needsReset)
        db.close()
    }
}
