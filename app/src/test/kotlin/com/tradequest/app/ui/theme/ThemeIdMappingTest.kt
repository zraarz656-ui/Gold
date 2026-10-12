package com.tradequest.app.ui.theme

import com.tradequest.chart.ChartTheme
import com.tradequest.chart.ThemeId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The persisted theme id must map back to the right chart theme, defaulting to Dark. */
class ThemeIdMappingTest {

    @Test
    fun knownIdsRoundTrip() {
        for (theme in ChartTheme.all) {
            assertEquals(theme.id, themeForId(theme.id.name).id)
        }
    }

    @Test
    fun idsAreCaseInsensitive() {
        assertEquals(ThemeId.COLORBLIND, themeForId("colorblind").id)
        assertEquals(ThemeId.LIGHT, themeForId("light").id)
    }

    @Test
    fun unknownOrEmptyIdsFallBackToDark() {
        assertEquals(ThemeId.DARK, themeForId("").id)
        assertEquals(ThemeId.DARK, themeForId("nonsense").id)
        assertEquals(ThemeId.DARK, themeForId("theme_id").id)
    }
}
