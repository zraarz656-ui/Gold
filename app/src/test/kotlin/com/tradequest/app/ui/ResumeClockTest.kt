package com.tradequest.app.ui

import java.time.Instant
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
}
