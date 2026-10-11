package com.tradequest.app.clock

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tick schedule and countdown are derived from the wall clock's next minute boundary,
 * never from the time a previous tick happened to fire. This is what makes a resumed clock
 * realign onto the boundary and the countdown match the phone clock's next :00.
 */
class LiveClockTest {

    private fun t(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test
    fun `the next boundary floors the seconds to 00`() {
        val at0942 = t("2026-07-01T09:09:42Z")
        assertEquals(t("2026-07-01T09:10:00Z"), LiveClock.nextMinuteBoundary(at0942))
        // On a boundary the next one is a full minute later.
        assertEquals(
            t("2026-07-01T09:10:00Z"),
            LiveClock.nextMinuteBoundary(t("2026-07-01T09:09:00Z")),
        )
        // A boundary is always a whole number of minutes.
        assertEquals(0L, Math.floorMod(LiveClock.nextMinuteBoundary(at0942), LiveClock.MINUTE_MS))
    }

    @Test
    fun `the countdown at 09 09 56 is four seconds to the boundary`() {
        val at0956 = t("2026-07-01T09:09:56Z")
        val boundary = LiveClock.nextMinuteBoundary(at0956)
        assertEquals(t("2026-07-01T09:10:00Z"), boundary)
        assertEquals(4_000L, boundary - at0956)
    }

    @Test
    fun `the delay to the next tick is measured from the boundary not the last tick`() {
        val at0942 = t("2026-07-01T09:09:42Z")
        val at0956 = t("2026-07-01T09:09:56Z")
        val at1000 = t("2026-07-01T09:10:00Z")

        // 09:09:42 is 18 s from 09:10:00; the delay is boundary - now + the small lag.
        assertEquals(18_000L + LiveClock.TICK_LAG_MS, LiveClock.delayToNextMinute(at0942))
        // The stale 09:09:42 tick is irrelevant: at 09:09:56 the wait is 4 s, not the 00:18
        // the device showed.
        assertEquals(4_000L + LiveClock.TICK_LAG_MS, LiveClock.delayToNextMinute(at0956))
        // Having just ticked on the boundary, the wait is a full minute.
        assertEquals(LiveClock.MINUTE_MS + LiveClock.TICK_LAG_MS, LiveClock.delayToNextMinute(at1000))
    }

    @Test
    fun `every tick the schedule produces lands on a minute boundary`() {
        var now = t("2026-07-01T09:09:42Z")
        repeat(5) {
            now += LiveClock.delayToNextMinute(now)
            // The loop wakes a hair past the boundary; the boundary itself is whole minutes.
            val boundary = now - LiveClock.TICK_LAG_MS
            assertEquals(0L, Math.floorMod(boundary, LiveClock.MINUTE_MS))
            assertEquals(LiveClock.TICK_LAG_MS, now - boundary)
        }
    }
}
