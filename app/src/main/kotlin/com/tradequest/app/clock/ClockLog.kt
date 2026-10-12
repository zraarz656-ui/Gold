package com.tradequest.app.clock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug-only in-memory ring of clock events for the on-device "Clock log" panel.
 *
 * The app is single-user and offline and the phone-only user cannot run adb/logcat, so the
 * device itself has to show what the clock did. Nothing here is read by trading logic: it is
 * an append-only diagnostic buffer. Fixed capacity keeps it from growing without bound.
 */
@Singleton
class ClockLog @Inject constructor() {

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    /** Newest first. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** Append an event, stamped with the wall clock, keeping only the newest [MAX]. */
    fun add(message: String) {
        val entry = Entry(Instant.now().toEpochMilli(), format(Instant.now().toEpochMilli()), message)
        _entries.value = (listOf(entry) + _entries.value).take(MAX)
    }

    /** The whole log as one copy-pasteable block, newest first. */
    fun asText(): String =
        (_entries.value.asReversed().joinToString("\n") { "${it.stamp} ${it.message}" })
            .ifEmpty { "(no clock events yet)" }

    data class Entry(val atMs: Long, val stamp: String, val message: String)

    companion object {
        const val MAX = 30
        private val HHMMSS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val ZONE: ZoneId get() = ZoneId.systemDefault()

        fun format(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZONE).format(HHMMSS)
    }
}
