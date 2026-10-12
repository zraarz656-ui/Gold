package com.tradequest.app.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.tradequest.app.clock.ClockLog
import com.tradequest.app.ui.theme.TradeQuestTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The debug clock log must actually lay out on a phone-sized screen: its header, its first
 * (newest) entry and its Copy button are all visible without scrolling past other content,
 * and Copy really writes the log to the clipboard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class ClockLogSectionTest {

    @get:Rule val compose = createComposeRule()

    private val entries = listOf(
        ClockLog.Entry(1_000L, "12:00:03", "lastTick WRITE: 12:00:00 (lag 5ms)"),
        ClockLog.Entry(1_000L, "12:00:02", "ViewModel ticker emit #1: ts=11:59:00"),
        ClockLog.Entry(1_000L, "12:00:01", "LiveClock tick: real=12:00:00 hist=12:00:00 visible=11:59:00"),
        ClockLog.Entry(1_000L, "12:00:00", "App start (ViewModel bootstrap)"),
    )

    @Test
    fun sectionRendersOnAPhoneSizedScreen() {
        compose.setContent {
            TradeQuestTheme {
                ClockLogSection(entries) { "log text" }
            }
        }

        compose.onNodeWithText("Clock log (debug)").assertIsDisplayed()
        compose.onNodeWithText("Copy log").assertIsDisplayed()
        // The newest entry is the first row under the header, so it is on screen.
        compose.onNodeWithText("lastTick WRITE: 12:00:00 (lag 5ms)").assertIsDisplayed()
        compose.onNodeWithText("12:00:03").assertIsDisplayed()
    }

    @Test
    fun copyLogPutsTheTextOnTheClipboard() {
        val body = entries.joinToString("\n") { "${it.stamp} ${it.message}" }
        compose.setContent {
            TradeQuestTheme {
                ClockLogSection(entries) { body }
            }
        }

        compose.onNodeWithText("Copy log").performClick()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        assertTrue("clipboard has the log, was: $copied", copied != null && copied.contains("lastTick WRITE"))
    }
}
