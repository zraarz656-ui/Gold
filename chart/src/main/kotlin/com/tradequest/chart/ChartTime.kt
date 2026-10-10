package com.tradequest.chart

import com.tradequest.engine.Timeframe
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Display formatting for the replayed clock.
 *
 * Stored timestamps stay UTC epoch millis. Everything shown to the user is the stored
 * timestamp plus a season's `displayOffsetMs` (which maps historical time onto "now"),
 * rendered in the device time zone. Pass a zone explicitly (defaulting to
 * [ZoneId.systemDefault]) so tests can pin one.
 */
fun formatTimeLabel(ts: Long, tf: Timeframe, zone: ZoneId = ZoneId.systemDefault()): String {
    val z = Instant.ofEpochMilli(ts).atZone(zone)
    return when (tf) {
        Timeframe.M1, Timeframe.M15, Timeframe.H1, Timeframe.H4 ->
            String.format("%02d:%02d", z.hour, z.minute)
        else -> z.format(DAY_MONTH)
    }
}

/** `yyyy-MM-dd HH:mm` in [zone], e.g. for the crosshair readout and trade rows. */
fun formatDateTime(ts: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val z = Instant.ofEpochMilli(ts).atZone(zone)
    return String.format("%04d-%02d-%02d %02d:%02d", z.year, z.monthValue, z.dayOfMonth, z.hour, z.minute)
}

/** `HH:mm` (local zone) for compact timestamps such as a close reason line. */
fun formatTime(ts: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val z = Instant.ofEpochMilli(ts).atZone(zone)
    return String.format("%02d:%02d", z.hour, z.minute)
}

/** `dd MMM HH:mm` (UTC) used by the news dialog placeholder. Kept for parity with the APK. */
fun formatShortDateTime(ts: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(ts).atZone(zone).format(SHORT)

/** `mm:ss` remaining until the current candle closes, clamped at zero. */
fun formatCountdown(remainingMs: Long): String {
    val total = (remainingMs.coerceAtLeast(0L) + 999L) / 1000L
    val minutes = total / 60
    val seconds = total % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM dd", Locale.US)
private val SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.US)

/** UTC zone id, handy as a fixed zone in tests and for stored/displayed offsets. */
val UTC: ZoneId = ZoneOffset.UTC
