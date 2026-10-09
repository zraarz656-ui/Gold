package com.tradequest.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The theme choice must survive a real DataStore write/read cycle. */
@RunWith(RobolectricTestRunner::class)
class PreferencesStoreTest {

    private val store = PreferencesStore(ApplicationProvider.getApplicationContext())

    @Test
    fun themeIdRoundTripsThroughDataStore() = runTest {
        store.setThemeId("LIGHT")
        assertEquals("LIGHT", store.themeIdOnce())
        assertEquals("LIGHT", store.themeId.first())

        store.setThemeId("COLORBLIND")
        assertEquals("COLORBLIND", store.themeIdOnce())
    }

    @Test
    fun unknownStoredValueIsReturnedVerbatimForTheCallerToValidate() = runTest {
        store.setThemeId("NOT_A_THEME")
        assertEquals("NOT_A_THEME", store.themeIdOnce())
    }

    @Test
    fun defaultThemeIsDark() {
        assertEquals("DARK", PreferencesStore.DEFAULT_THEME)
        assertEquals("theme_id", PreferencesStore.THEME_KEY)
    }
}
