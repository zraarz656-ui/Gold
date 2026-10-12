package com.tradequest.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tradequest.app.ui.theme.TradeQuestTheme
import com.tradequest.chart.ChartTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The chart must shout when placeholder data is live, and block until reset when the
 *  dataset range has moved. */
@RunWith(RobolectricTestRunner::class)
class FakeDataBannerTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun fakeBannerIsVisibleAndSaysFakeData() {
        compose.setContent { TradeQuestTheme(ChartTheme.DARK) { FakeDataBanner() } }
        compose.onNodeWithText("FAKE DATA — generated placeholder series, not real history")
            .assertIsDisplayed()
    }

    @Test
    fun datasetChangedScreenOffersResetAndInvokesIt() {
        var reset = false
        compose.setContent {
            TradeQuestTheme(ChartTheme.DARK) { DatasetChangedScreen(onReset = { reset = true }) }
        }
        compose.onNodeWithText("Dataset changed — reset season").assertIsDisplayed()
        compose.onNodeWithText("Reset season").performClick()
        assertTrue("reset callback must fire", reset)
    }
}
