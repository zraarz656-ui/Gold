package com.tradequest.app.ui

import com.tradequest.data.AccountCheckpoint
import com.tradequest.data.AccountStateDto
import com.tradequest.data.DailyStats
import com.tradequest.data.SettingEntity
import com.tradequest.engine.MarketCalendar
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * "P&L values look strange": with no positions, the strip's Day P&L fell back to
 * season.startBalance, so it silently showed the whole-season P&L instead of today's.
 * The engine records the day-start equity at every rollover; the strip must use it and
 * persist a DailyStats row.
 */
@RunWith(RobolectricTestRunner::class)
class DayPnlTest {

    private val h = TradingHarness()

    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        h.setUp()
    }

    @After
    fun tearDown() = h.tearDown()

    @Test
    fun dayPnlIsMeasuredFromTheDayStartNotTheSeasonStart() = runBlocking {
        h.seed()
        // A season up 500 since yesterday: season start 10,000, today's open equity 10,500.
        val checkpoint = AccountStateDto(
            balance = 10_500.0,
            dayTs = MarketCalendar.dayStart(h.openNow),
            dayStartEquity = 10_500.0,
        )
        h.db.settingsDao().put(SettingEntity(AccountCheckpoint.KEY, AccountCheckpoint.encode(checkpoint)))

        val vm = h.buildViewModel()
        h.awaitUntil { vm.ready.value && vm.strip.value.equity > 0.0 }

        assertEquals(10_500.0, vm.strip.value.equity, 0.01)
        assertEquals(
            "flat, so today's P&L is zero — not the 500 made before today",
            0.0,
            vm.strip.value.dayPnl,
            0.01,
        )

        val day = h.db.dailyStatsDao()
            .forDay(1L, MarketCalendar.dayStart(h.openNow))
        assertNotNull("a DailyStats row must be persisted for the day", day)
        assertEquals(10_500.0, day!!.startEquity, 0.01)
        assertTrue(day.pnl == day.pnl) // not NaN
    }
}
