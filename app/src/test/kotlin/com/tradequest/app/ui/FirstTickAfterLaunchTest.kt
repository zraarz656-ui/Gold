package com.tradequest.app.ui

import com.tradequest.app.clock.ClockLog
import com.tradequest.app.clock.LiveClock
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * On-device clock log: a fresh launch at :20 must show, in order, the app start, the clock
 * start, the scheduled wait, the tick, the ViewModel emit and the lastTick write, and
 * lastTick must end up at the :00 boundary by :00+2s.
 */
@RunWith(RobolectricTestRunner::class)
class FirstTickAfterLaunchTest {
    private val h = TradingHarness()
    @Before fun setUp() = h.setUp()
    @After fun tearDown() = h.tearDown()
    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun launchAndCheck(launchIso: String, boundaryIso: String) {
        h.clockMs = at(launchIso)
        h.seed(anchor = at("2026-07-01T09:09:00Z"))
        val clock = h.buildLiveClock()
        clock.start(CoroutineScope(Dispatchers.Main.immediate))
        val vm = h.buildViewModel()
        h.awaitUntil { vm.ready.value && vm.quote.value.bid > 0.0 }
        // Advance to 2 s past the boundary.
        h.clockMs = at(boundaryIso) + 2_000L
        h.awaitUntil(timeoutMs = 20_000L) { vm.lastTickStampCount >= 1 }

        // lastTick is the boundary, on the minute.
        assertEquals(at(boundaryIso), vm.lastTickWallMs.value)
        assertEquals(0L, Math.floorMod(vm.lastTickWallMs.value, LiveClock.MINUTE_MS))

        // The on-device log holds the whole launch sequence, newest first.
        val entries = vm.clockLogEntries.value
        assertTrue("log is capped", entries.size <= ClockLog.MAX)
        val oldestToNewest = entries.asReversed().map { it.message }
        fun idx(fragment: String) = oldestToNewest.indexOfFirst { it.contains(fragment) }
        assertTrue("app start logged", oldestToNewest.any { it.startsWith("App start") })
        assertTrue("clock start", idx("LiveClock start") >= 0)
        assertTrue("scheduled wait after clock start", idx("LiveClock start") < idx("LiveClock schedule"))
        assertTrue("tick after the wait", idx("LiveClock schedule") < idx("LiveClock tick"))
        assertTrue("vm emit after the tick", idx("LiveClock tick") < idx("ticker emit"))
        assertTrue("lastTick write after the emit", idx("ticker emit") < idx("lastTick WRITE"))
        assertTrue("the 2 s lag is reported as late", oldestToNewest.any { it.contains("LATE") })

        // Each entry carries an HH:mm:ss stamp and the copy text contains the whole log.
        assertTrue(entries.all { it.stamp.matches(Regex("\\d{2}:\\d{2}:\\d{2}")) })
        assertTrue(vm.clockLogText().contains("lastTick WRITE"))
    }

    @Test fun freshLaunchAt20SecsLogsTheOrderedSequenceAndStampsLastTick() =
        launchAndCheck("2026-07-01T09:09:20Z", "2026-07-01T09:10:00Z")

    @Test fun freshLaunchAt50SecsLogsTheOrderedSequenceAndStampsLastTick() =
        launchAndCheck("2026-07-01T09:09:50Z", "2026-07-01T09:10:00Z")
}
