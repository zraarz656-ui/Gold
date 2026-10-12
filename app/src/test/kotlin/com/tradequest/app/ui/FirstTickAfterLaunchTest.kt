package com.tradequest.app.ui

import android.os.Looper
import com.tradequest.app.clock.LiveClock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class FirstTickAfterLaunchTest {
    private val h = TradingHarness()
    @Before fun setUp() = h.setUp()
    @After fun tearDown() = h.tearDown()
    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun launchAndCheck(launchIso: String, boundaryIso: String) {
        val buf = java.io.ByteArrayOutputStream()
        org.robolectric.shadows.ShadowLog.setupLogging()
        org.robolectric.shadows.ShadowLog.clear()
        org.robolectric.shadows.ShadowLog.stream = java.io.PrintStream(buf, true)

        h.clockMs = at(launchIso)
        h.seed(anchor = at("2026-07-01T09:09:00Z"))
        val clock = h.buildLiveClock()
        clock.start(CoroutineScope(Dispatchers.Main.immediate))
        val vm = h.buildViewModel()
        h.awaitUntil { vm.ready.value && vm.quote.value.bid > 0.0 }
        // Advance to 2 s past the boundary.
        h.clockMs = at(boundaryIso) + 2_000L
        h.awaitUntil(timeoutMs = 20_000L) { vm.lastTickStampCount >= 1 }
        assertEquals(at(boundaryIso), vm.lastTickWallMs.value)
        assertEquals(0L, Math.floorMod(vm.lastTickWallMs.value, LiveClock.MINUTE_MS))

        println("==== launch=$launchIso boundary=$boundaryIso ====")
        println("lastTick=${vm.lastTickWallMs.value} expect=${at(boundaryIso)}")
        buf.toString(Charsets.UTF_8.name()).lines()
            .filter { it.contains("TradeQuest") }
            .forEach { println(it) }
        println("==== end ====")
    }

    @Test fun freshLaunchAt20SecsStampsTheNextBoundaryByPlus2s() =
        launchAndCheck("2026-07-01T09:09:20Z", "2026-07-01T09:10:00Z")

    @Test fun freshLaunchAt50SecsStampsTheNextBoundaryByPlus2s() =
        launchAndCheck("2026-07-01T09:09:50Z", "2026-07-01T09:10:00Z")
}
