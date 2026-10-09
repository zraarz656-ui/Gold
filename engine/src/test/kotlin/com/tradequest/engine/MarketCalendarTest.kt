package com.tradequest.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The weekend closure must match the FX rollover, including around DST. */
class MarketCalendarTest {

    @Test
    fun `closed from Friday rollover to Sunday rollover`() {
        // Friday 2024-03-08: open before 17:00 NY, closed from 17:00.
        assertFalse(MarketCalendar.isClosed(TestSupport.ny(2024, 3, 8, 16, 59)))
        assertTrue(MarketCalendar.isClosed(TestSupport.ny(2024, 3, 8, 17, 0)))

        // Saturday is closed all day.
        assertTrue(MarketCalendar.isClosed(TestSupport.ny(2024, 3, 9, 12, 0)))

        // Sunday: closed until 17:00, open after.
        assertTrue(MarketCalendar.isClosed(TestSupport.ny(2024, 3, 10, 16, 59)))
        assertFalse(MarketCalendar.isClosed(TestSupport.ny(2024, 3, 10, 17, 0)))

        // A normal mid-week minute is open.
        assertFalse(MarketCalendar.isClosed(TestSupport.ny(2024, 3, 13, 10, 0)))
    }

    @Test
    fun `closure follows the rollover across a DST change`() {
        // US DST starts 2024-03-10. The Friday before is still EST (-5), the Sunday after
        // the switch is EDT (-4); both rollovers are 17:00 local, i.e. different UTC hours.
        val friday = TestSupport.ny(2024, 3, 8, 17, 0)
        val sunday = TestSupport.ny(2024, 3, 10, 17, 0)
        assertTrue(MarketCalendar.isClosed(friday))
        assertFalse(MarketCalendar.isClosed(sunday))
    }

    @Test
    fun `isWeekend matches the closure and isWeekdayOpen is its inverse`() {
        val fridayOpen = TestSupport.ny(2024, 3, 8, 16, 59)
        val fridayRoll = TestSupport.ny(2024, 3, 8, 17, 0)
        val saturday = TestSupport.ny(2024, 3, 9, 12, 0)
        val sundayClosed = TestSupport.ny(2024, 3, 10, 16, 59)
        val sundayOpen = TestSupport.ny(2024, 3, 10, 17, 0)
        val midweek = TestSupport.ny(2024, 3, 13, 10, 0)

        for (ts in listOf(fridayOpen, fridayRoll, saturday, sundayClosed, sundayOpen, midweek)) {
            assertEquals(MarketCalendar.isClosed(ts), MarketCalendar.isWeekend(ts))
            assertEquals(!MarketCalendar.isClosed(ts), MarketCalendar.isWeekdayOpen(ts))
        }
        // The weekend shutdown itself is "weekend"; a normal session minute is not.
        assertTrue(MarketCalendar.isWeekend(saturday))
        assertFalse(MarketCalendar.isWeekend(midweek))
    }
}
