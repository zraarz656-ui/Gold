package com.tradequest.app.ui

import android.os.Looper
import com.tradequest.app.clock.LiveClock
import com.tradequest.chart.ChartTheme
import com.tradequest.chart.PriceLabelSize
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog

/**
 * Regression for the off-minute `lastTick` stamp: only a real minute-boundary tick may write
 * [TradingViewModel.lastTickWallMs]. `refreshDerived()` (a UI recomposition / the Data panel),
 * the settings sheet (its Data-panel refresh and its theme/label/countdown writes) and the
 * 4 Hz display ticker must never touch it.
 *
 * The write is counted ([TradingViewModel.lastTickStampCount]) because at these seconds the
 * value is 0 → 0, so equality alone cannot prove the absence of a write.
 */
@RunWith(RobolectricTestRunner::class)
class LastTickWriterTest {
    private val h = TradingHarness()

    @Before fun setUp() = h.setUp()
    @After fun tearDown() = h.tearDown()

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    /** Advance the main looper by [ms] so the 4 Hz display ticker and post-launch work run. */
    private fun pump(ms: Long) {
        val looper = shadowOf(Looper.getMainLooper())
        var elapsed = 0L
        while (elapsed < ms) {
            looper.idleFor(Duration.ofMillis(50))
            elapsed += 50
        }
    }

    @Test
    fun refreshDerivedAndTheSettingsSheetAtOffMinuteSecondsDoNotWriteLastTick() {
        // Open at 12:00:42, off the minute; the seeded history ends at 11:59.
        h.clockMs = at("2026-07-01T12:00:42Z")
        h.seed()
        val vm = h.buildViewModel()
        h.awaitUntil { vm.ready.value && vm.quote.value.bid > 0.0 }

        // No boundary tick has fired yet.
        assertEquals(0L, vm.lastTickWallMs.value)
        val stampsBefore = vm.lastTickStampCount

        // refreshDerived is what a UI recomposition / the Data panel drives.
        runBlocking { vm.refreshDerived() }
        assertEquals("refreshDerived must not stamp lastTick", 0L, vm.lastTickWallMs.value)
        assertEquals(stampsBefore, vm.lastTickStampCount)

        // Opening the settings sheet refreshes the Data panel (its Refresh button).
        runBlocking { vm.refreshDataStats() }
        assertEquals("Data panel must not stamp lastTick", 0L, vm.lastTickWallMs.value)
        assertEquals(stampsBefore, vm.lastTickStampCount)

        // The other settings-sheet write paths: theme, label size, countdown.
        vm.setTheme(ChartTheme.all.last())
        vm.setLabelSize(PriceLabelSize.LARGE)
        vm.setShowCountdown(false)
        pump(500L)
        assertEquals("settings changes must not stamp lastTick", 0L, vm.lastTickWallMs.value)
        assertEquals(stampsBefore, vm.lastTickStampCount)

        // The 4 Hz display ticker runs continuously; let it run ~2 s of frames.
        pump(2_000L)
        assertEquals("display ticker must not stamp lastTick", 0L, vm.lastTickWallMs.value)
        assertEquals(stampsBefore, vm.lastTickStampCount)
    }

    @Test
    fun onlyTheBoundaryTickWritesLastTickAndItIsAlwaysOnTheMinute() {
        h.clockMs = at("2026-07-01T09:09:00Z")
        h.seed(anchor = at("2026-07-01T09:09:00Z"))
        val vm = h.buildViewModel()
        val clock = h.buildLiveClock()
        h.awaitUntil { vm.ready.value && vm.quote.value.bid > 0.0 }
        clock.start(CoroutineScope(Dispatchers.Main.immediate))
        assertEquals(0, vm.lastTickStampCount)

        // Reach the boundary; the ticker fires on it and stamps.
        h.clockMs = at("2026-07-01T09:10:00Z")
        h.awaitUntil(timeoutMs = 15_000L) { vm.lastTickStampCount >= 1 }
        assertEquals(at("2026-07-01T09:10:00Z"), vm.lastTickWallMs.value)
        assertEquals(0L, Math.floorMod(vm.lastTickWallMs.value, LiveClock.MINUTE_MS))
        assertEquals(1, vm.lastTickStampCount)

        // Off-minute churn after the tick must not move it.
        h.clockMs = at("2026-07-01T09:10:37Z")
        runBlocking { vm.refreshDerived() }
        vm.onResume()
        h.awaitUntil { vm.controller.state.countdownMs == 23_000L }
        assertEquals(at("2026-07-01T09:10:00Z"), vm.lastTickWallMs.value)
        assertEquals(1, vm.lastTickStampCount)
    }

    /**
     * A 3-minute replay with the settings sheet opened/closed and refreshDerived driven at
     * off-minute seconds. Prints every lastTick log line so the run is auditable, and asserts
     * every stamp is on a minute boundary and none is off-minute.
     */
    @Test
    fun threeMinuteRunLogsOnlyBoundaryLastTickStamps() {
        val buf = java.io.ByteArrayOutputStream()
        ShadowLog.setupLogging()
        ShadowLog.clear()
        ShadowLog.stream = java.io.PrintStream(buf, true)

        h.clockMs = at("2026-07-01T09:09:00Z")
        h.seed(anchor = at("2026-07-01T09:09:00Z"))
        val vm = h.buildViewModel()
        val clock = h.buildLiveClock()
        h.awaitUntil { vm.ready.value && vm.quote.value.bid > 0.0 }
        clock.start(CoroutineScope(Dispatchers.Main.immediate))

        // Walk three minutes, poking the display/settings paths off the boundary each minute.
        for (minute in 1..3) {
            val base = at("2026-07-01T09:09:00Z") + minute * 60_000L
            h.clockMs = base + 37_000L // off-minute: the bad stamp used to appear here
            runBlocking { vm.refreshDerived() }
            runBlocking { vm.refreshDataStats() }
            vm.setTheme(ChartTheme.all[minute % ChartTheme.all.size])
            vm.setLabelSize(PriceLabelSize.LARGE)
            vm.setShowCountdown(minute % 2 == 0)
            pump(300L)
            h.clockMs = base // reach the boundary; the ticker must stamp here
            h.awaitUntil(timeoutMs = 15_000L) { vm.lastTickStampCount >= minute }
        }

        val lines = buf.toString(Charsets.UTF_8.name()).lines()
            .filter { it.contains("TradeQuest") && it.contains("lastTick") }
        println("---- lastTick log lines (3-minute run) ----")
        lines.forEach { println(it) }
        println("---- end (${lines.size} lines, ${vm.lastTickStampCount} stamps) ----")

        assertEquals("three boundary minutes must stamp exactly three times", 3, vm.lastTickStampCount)
        assertEquals(0L, Math.floorMod(vm.lastTickWallMs.value, LiveClock.MINUTE_MS))
        val offBoundary = lines.filter { it.contains("lastTick not stamped") }
        assertEquals("no off-minute lastTick write may be logged", 0, offBoundary.size)
        val stamps = lines.filter { it.contains("lastTick stamped") }
        assertEquals(3, stamps.size)
        stamps.forEach { assert(it.contains("boundary=")) }
    }
}
