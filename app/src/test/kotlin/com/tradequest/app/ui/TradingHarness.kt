package com.tradequest.app.ui

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tradequest.data.AssetSource
import com.tradequest.data.Candle1m
import com.tradequest.data.CandleRepository
import com.tradequest.data.CatchUpProcessor
import com.tradequest.data.PreferencesStore
import com.tradequest.data.Season
import com.tradequest.data.SeasonRepository
import com.tradequest.data.SeasonStatus
import com.tradequest.data.SettingEntity
import com.tradequest.data.SettingsRepository
import com.tradequest.data.TradeOrder
import com.tradequest.data.TradeQuestDatabase
import com.tradequest.data.TradingRepository
import java.io.InputStream
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf

/**
 * Shared Robolectric harness for driving the real [TradingViewModel] against an in-memory
 * Room database. Nothing is stubbed below the ViewModel: the real clock, repositories and
 * catch-up are used, so readiness, sequencing and persistence bugs surface exactly as they
 * do on device.
 */
class TradingHarness {

    // A Wednesday at 12:00 UTC: the replayed market is open.
    val openNow: Long = Instant.parse("2026-07-01T12:00:00Z").toEpochMilli()
    val min = 60_000L

    lateinit var db: TradeQuestDatabase
    lateinit var candles: CandleRepository
    private lateinit var context: Context

    private val assets = object : AssetSource {
        override fun open(name: String): InputStream = java.io.ByteArrayInputStream(ByteArray(0))
        override fun exists(name: String): Boolean = false
        override fun size(name: String): Long = -1L
    }

    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, TradeQuestDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    fun tearDown() = db.close()

    fun buildViewModel(): TradingViewModel = TradingViewModel(
        db = db,
        seasons = SeasonRepository(db) { openNow },
        trading = TradingRepository(db),
        candles = CandleRepository(db).also { candles = it },
        settings = SettingsRepository(db),
        preferences = PreferencesStore(context),
        catchUp = CatchUpProcessor(db),
        assetSource = assets,
    )

    /** Seed candles up to (but not including) histNow plus a season and import flags. */
    fun seed() = runBlocking {
        val first = openNow - 100 * min
        db.candleDao().insertAll(
            (0 until 100).map { i ->
                val ts = first + i * min
                val o = 2400.0 + i * 0.01
                Candle1m(ts, o, o + 0.2, o - 0.2, o + 0.05, 100.0)
            },
        )
        db.seasonDao().insert(
            Season(
                startedAt = openNow - 7 * 24 * 60 * min,
                offsetMs = 0L,
                startBalance = 10_000.0,
                status = SeasonStatus.ACTIVE,
                lastProcessedTs = openNow - 10 * min,
            ),
        )
        db.settingsDao().put(SettingEntity(SettingsRepository.IMPORT_DONE, "100000"))
        db.settingsDao().put(SettingEntity(SettingsRepository.DATA_SOURCE, "BUNDLED"))
    }

    /** Pump the main looper until [condition] holds (background Room work needs this). */
    fun awaitUntil(timeoutMs: Long = 20_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            // advance() lets coroutines parked on the main dispatcher's delay() (the 4 Hz
            // display ticker) run; idle() alone only fires tasks already due.
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60))
            if (condition()) return
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("condition not met within ${timeoutMs}ms", condition())
    }

    inline fun <T> onMain(noinline block: () -> T): T {
        var result: T? = null
        shadowOf(Looper.getMainLooper()).runPaused { result = block() }
        return result!!
    }

    fun live(seasonId: Long): List<TradeOrder> = runBlocking { db.tradeOrderDao().live(seasonId) }

    fun closed(seasonId: Long): List<TradeOrder> = runBlocking { db.tradeOrderDao().closed(seasonId) }
}
