package com.tradequest.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider

/** Shared in-memory Room database for the data-module tests. */
object TestDb {
    fun open(): TradeQuestDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        TradeQuestDatabase::class.java,
    ).allowMainThreadQueries().build()

    /** A season whose catch-up will start at [firstCandleTs]. */
    suspend fun seedSeason(
        db: TradeQuestDatabase,
        firstCandleTs: Long,
        startBalance: Double = 10_000.0,
    ): Season {
        val id = db.seasonDao().insert(
            Season(
                startedAt = firstCandleTs,
                offsetMs = 0L,
                startBalance = startBalance,
                status = SeasonStatus.ACTIVE,
                lastProcessedTs = firstCandleTs - 60_000L,
            ),
        )
        return db.seasonDao().byId(id)!!
    }

    /** Candle series with a small deterministic drift so orders/limits get exercised. */
    fun candles(count: Int, startTs: Long, stepMs: Long = 60_000L): List<Candle1m> {
        var price = 2400.0
        return (0 until count).map { i ->
            val ts = startTs + i * stepMs
            val o = price
            val c = o + if (i % 3 == 0) 0.4 else -0.25
            price = c
            Candle1m(ts, o, maxOf(o, c) + 0.2, minOf(o, c) - 0.2, c, 100.0 + i)
        }
    }
}
