package com.tradequest.app.ui

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tradequest.data.AssetSource
import com.tradequest.data.Candle1m
import com.tradequest.data.CandleRepository
import com.tradequest.data.CatchUpProcessor
import com.tradequest.data.OrderRequest
import com.tradequest.data.OrderStatus
import com.tradequest.data.PreferencesStore
import com.tradequest.data.Season
import com.tradequest.data.SeasonRepository
import com.tradequest.data.SeasonStatus
import com.tradequest.data.SettingEntity
import com.tradequest.data.SettingsRepository
import com.tradequest.data.TradeOrder
import com.tradequest.data.TradeOrderDao
import com.tradequest.data.TradeQuestDatabase
import com.tradequest.data.TradingRepository
import com.tradequest.engine.OrderType
import com.tradequest.engine.Side
import java.io.InputStream
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog

/**
 * Reproduction for "trading is broken after minimize/restore".
 *
 * Drives the real [TradingViewModel] through launch, a simulated background/restore cycle
 * (ON_RESUME is all the app handles; ON_STOP is a no-op) and a market buy. Uses the real
 * Room database, clock and repositories, so a stuck flag, a cancelled scope or a held mutex
 * would surface here exactly as it does on device.
 */
@RunWith(RobolectricTestRunner::class)
class OrderSubmitAfterResumeTest {

    // A Wednesday at 12:00 UTC: the replayed market is open.
    private val openNow = Instant.parse("2026-07-01T12:00:00Z").toEpochMilli()
    private val min = 60_000L

    private lateinit var db: TradeQuestDatabase
    private lateinit var context: Context

    private val assets = object : AssetSource {
        override fun open(name: String): InputStream = java.io.ByteArrayInputStream(ByteArray(0))
        override fun exists(name: String): Boolean = false
        override fun size(name: String): Long = -1L
    }

    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, TradeQuestDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun buildViewModel(): TradingViewModel = TradingViewModel(
        db = db,
        seasons = SeasonRepository(db) { openNow },
        trading = TradingRepository(db),
        candles = CandleRepository(db),
        settings = SettingsRepository(db),
        preferences = PreferencesStore(context),
        catchUp = CatchUpProcessor(db),
        assetSource = assets,
    )

    /** Seed candles up to (but not including) histNow plus a season and import flags. */
    private fun seed() = runBlocking {
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
    private fun awaitUntil(timeoutMs: Long = 20_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("condition not met within ${timeoutMs}ms", condition())
    }

    private inline fun <T> onMain(noinline block: () -> T): T {
        var result: T? = null
        shadowOf(Looper.getMainLooper()).runPaused { result = block() }
        return result!!
    }

    private fun live(seasonId: Long): List<TradeOrder> =
        runBlocking { db.tradeOrderDao().live(seasonId) }

    @Test
    fun submitIsAcceptedAsSoonAsTheQuoteIsLive() {
        // Before the fix this was the "trading is broken after minimize/restore" window:
        // the chart shows live prices (_quote set in loadInitialWindow) while `ready` is
        // still false, so every submit early-returned with
        //   W/TradeQuest: placeOrder: early return - Market data is still loading
        // This test waits only for a live quote, then submits.
        seed()
        val vm = buildViewModel()
        awaitUntil { vm.quote.value.bid > 0.0 }

        onMain { vm.placeOrder(OrderRequest(OrderType.MARKET, 0.10, side = Side.LONG)) }
        awaitUntil { live(1L).isNotEmpty() }

        assertEquals(1, live(1L).size)
        assertNull("a submit with a live quote must never be dropped: ${vm.submitError.value}", vm.submitError.value)
    }

    @Test
    fun marketBuyAfterResumeOpensExactlyOnePosition() {
        seed()
        val vm = buildViewModel()
        awaitUntil { vm.ready.value && vm.quote.value.bid > 0.0 }

        // Minimize then restore. The app only acts on ON_RESUME.
        onMain { vm.onResume() }
        awaitUntil { true }

        onMain { vm.placeOrder(OrderRequest(OrderType.MARKET, 0.10, side = Side.LONG)) }
        awaitUntil { live(1L).isNotEmpty() }

        val rows = live(1L)
        assertNull("submit must not report an error: ${vm.submitError.value}", vm.submitError.value)
        assertEquals("exactly one live order expected", 1, rows.size)
        assertEquals(OrderStatus.OPEN, rows.single().status)
        assertNotNull(rows.single().entryPrice)
    }

    @Test
    fun submitBeforeReadyReportsAReasonInsteadOfFailingSilently() {
        val vm = buildViewModel()
        onMain { vm.placeOrder(OrderRequest(OrderType.MARKET, 0.10, side = Side.LONG)) }
        awaitUntil { vm.submitError.value != null }

        assertNotNull("a rejected submit must surface a reason", vm.submitError.value)
        assertTrue(runBlocking { db.tradeOrderDao().closed(1L).isEmpty() })
    }

    @Test
    fun submitIsStillAcceptedAfterRepeatedResumes() {
        seed()
        val vm = buildViewModel()
        awaitUntil { vm.ready.value && vm.quote.value.bid > 0.0 }
        repeat(3) {
            onMain { vm.onResume() }
            awaitUntil { true }
        }
        onMain { vm.placeOrder(OrderRequest(OrderType.MARKET, 0.10, side = Side.LONG)) }
        awaitUntil { live(1L).isNotEmpty() }
        assertEquals(1, live(1L).size)
        assertNull(vm.submitError.value)
    }
}
