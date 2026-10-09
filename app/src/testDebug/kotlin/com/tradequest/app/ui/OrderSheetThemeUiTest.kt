package com.tradequest.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.tradequest.app.ui.theme.TradeQuestTheme
import com.tradequest.chart.ChartTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Opens the order sheet and types into its fields, then re-renders it in every theme. This
 * is the regression guard for the unreadable-input bug: the sheet must accept edits and
 * keep the typed values visible in all four themes.
 */
@RunWith(RobolectricTestRunner::class)
class OrderSheetThemeUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun orderSheetAcceptsEditsInEveryTheme() {
        var theme by mutableStateOf(ChartTheme.all.first())
        compose.setContent {
            TradeQuestTheme(theme) {
                OrderSheet(
                    quote = Quote(bid = 2400.0, ask = 2400.5, spread = 0.5),
                    equity = 10_000.0,
                    riskPercent = 1.0,
                    onDismiss = {},
                    onPlace = {},
                )
            }
        }

        // A limit order reveals the trigger-price field as well.
        compose.onNodeWithText("Buy limit").performClick()

        // Fields, in layout order: Trigger price, Lots, SL, TP, Trailing distance.
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextReplacement("2401.50")
        fields[1].performTextReplacement("0.25")
        fields[2].performTextReplacement("2395.00")
        fields[3].performTextReplacement("2410.00")
        fields[4].performTextReplacement("3.00")

        for (t in ChartTheme.all) {
            theme = t
            compose.waitForIdle()
            compose.onNodeWithText("2401.50").assertIsDisplayed()
            compose.onNodeWithText("0.25").assertIsDisplayed()
            compose.onNodeWithText("2395.00").assertIsDisplayed()
            compose.onNodeWithText("2410.00").assertIsDisplayed()
            compose.onNodeWithText("3.00").assertIsDisplayed()
        }
    }
}
