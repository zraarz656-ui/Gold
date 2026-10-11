package com.tradequest.app.ui

import com.tradequest.app.clock.LiveClock
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Device regression: the screen was suspended on 09:09:xx and resumed at 09:09:56. The
 * replayed clock showed lastTick = 09:09:42 (off the minute) and a countdown of 00:18, when
 * 09:09:56 is only 00:04 from the next :00.
 *
 * The schedule math itself is covered by [com.tradequest.app.clock.LiveClockTest]; here we
 * prove the display path: open does not stamp an off-minute lastTick, and resume derives the
 * countdown from the next minute boundary.
 */
@RunWith(RobolectricTestRunner::class)
class ResumeClockTest {
    private val h = TradingHarness()

    @Before fun setUp() = h.setUp()
    @After fun tearDown() = h.tearDown()

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test
    fun resumeDerivesTheCountdownFromTheBoundaryAndNeverStampsAnOffMinuteTick() {
        // Open at 12:00:42, off the minute, as the clock stood when the screen slept. The
        // seeded history ends at 11:59, so the last closed candle is 11:59:00.
        h.clockMs = at("2026-07-01T12:00:42Z")
        h.seed()
        val vm = h.buildViewModel()
        h.awaitUntil { vm.ready.value && vm.quote.value.bid > 0.0 }

        // App open does not stamp the off-minute sentinel value as a real lastTick.
        assertEquals("no off-minute tick on open", 0L, vm.lastTickWallMs.value)

        // Resume at 12:00:56: the countdown is 4 s to 12:01:00, not the 00:18 measured from
        // the stale 12:00:42 instant.
        h.clockMs = at("2026-07-01T12:00:56Z")
        vm.onResume()
        h.awaitUntil { vm.controller.state.countdownMs == 4_000L }
        assertEquals(4_000L, vm.controller.state.countdownMs)
        assertEquals(0L, vm.lastTickWallMs.value)
    }

    @Test
    fun resumeStopsTheClockAt09_09_42AndTheNextBoundaryTickLandsOn09_10_00() {
        // Open exactly on 09:09:00; history ends at 09:08 so the last closed candle is 09:08:00.
        h.clockMs = at("2026-07-01T09:09:00Z")
        h.seed(anchor = at("2026-07-01T09:09:00Z"))
        val vm = h.buildViewModel()
        val clock = h.buildLiveClock()
        h.awaitUntil { vm.ready.value && vm.quote.value.bid > 0.0 }
        assertEquals(0L, vm.lastTickWallMs.value)

        // App open starts the ticker, which waits for the next boundary and emits nothing yet.
        clock.start(CoroutineScope(Dispatchers.Main.immediate))

        // The clock stood at 09:09:42 (off the minute) when the screen slept, then resumed at
        // 09:09:56. realign re-bases the ticker onto the next boundary and catch-up runs.
        h.clockMs = at("2026-07-01T09:09:56Z")
        clock.realign(CoroutineScope(Dispatchers.Main.immediate))
        vm.onResume()
        h.awaitUntil { vm.controller.state.countdownMs == 4_000L }

        // Countdown is 00:04 at 09:09:56, not 00:18 from the stale 09:09:42 instant.
        assertEquals(4_000L, vm.controller.state.countdownMs)
        // Neither stop nor resume stamped an off-minute tick.
        assertEquals(0L, vm.lastTickWallMs.value)

        // Reach the boundary; the ticker fires there, with the clock reading 09:10:00.
        h.clockMs = at("2026-07-01T09:10:00Z")
        h.awaitUntil(timeoutMs = 15_000L) {
            vm.lastTickWallMs.value == at("2026-07-01T09:10:00Z")
        }

        // lastTick is the boundary, 09:10:00, not the stopped 09:09:42 instant.
        assertEquals(at("2026-07-01T09:10:00Z"), vm.lastTickWallMs.value)
        assertEquals(0L, Math.floorMod(vm.lastTickWallMs.value, LiveClock.MINUTE_MS))
    }
}
