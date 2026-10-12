package com.tradequest.chart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The entry-tag merge rule: entries within 20dp collapse into one grouped tag. */
class EntryGroupsTest {

    private fun point(id: Long, y: Float, pnl: Double? = null) =
        EntryPoint(ids = listOf(id), y = y, pnl = pnl, positive = (pnl ?: 0.0) >= 0.0)

    @Test
    fun `two entries within the gap merge into one group`() {
        val groups = EntryGroups.cluster(
            listOf(point(1, 100f, -3.0), point(2, 110f, -2.10)),
            EntryGroups.MIN_GAP_DP,
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].count)
        assertEquals(listOf(1L, 2L), groups[0].ids)
        assertEquals(-5.10, groups[0].pnl!!, 1e-9)
    }

    @Test
    fun `entries further apart than the gap stay separate`() {
        val groups = EntryGroups.cluster(
            listOf(point(1, 100f), point(2, 130f)),
            EntryGroups.MIN_GAP_DP,
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `a chain of close entries merges as one run`() {
        val groups = EntryGroups.cluster(
            listOf(point(1, 0f), point(2, 10f), point(3, 18f)),
            EntryGroups.MIN_GAP_DP,
        )
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].count)
    }

    @Test
    fun `the group centre sits midway between the outermost entries`() {
        val groups = EntryGroups.cluster(listOf(point(1, 100f), point(2, 108f)), 20f)
        assertEquals(104f, groups[0].centerY, 1e-4f)
    }

    @Test
    fun `pnl is null when no entry carries one`() {
        val groups = EntryGroups.cluster(listOf(point(1, 100f), point(2, 105f)), 20f)
        assertNull(groups[0].pnl)
    }

    @Test
    fun `the grouped tag text names the count and the money`() {
        val groups = EntryGroups.cluster(
            listOf(point(1, 100f, -3.0), point(2, 105f, -2.10)),
            20f,
        )
        assertEquals("2 pos  -5.10", groupTagText(groups[0]))
    }
}
