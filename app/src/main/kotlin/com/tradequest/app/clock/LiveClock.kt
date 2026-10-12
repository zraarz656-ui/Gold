package com.tradequest.app.clock

import com.tradequest.data.CandleRepository
import com.tradequest.data.Season
import com.tradequest.data.SeasonRepository
import com.tradequest.engine.ClockEngine
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** The replayed clock: real time shifted back by the season offset. */
data class ClockTick(
    val realNow: Long,
    val histNow: Long,
    val lastVisibleCandleTs: Long,
)

/**
 * Drives the one-per-minute tick.
 *
 * Every minute it recomputes `histNow = realNow - offsetMs` and publishes the newly
 * closed candle timestamp to [CandleRepository], which the chart observes. The tick is
 * aligned to the minute boundary so a candle appears as soon as it closes.
 */
@Singleton
class LiveClock @Inject constructor(
    private val seasons: SeasonRepository,
    private val candles: CandleRepository,
    private val clockLog: ClockLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _tick = MutableStateFlow<ClockTick?>(null)
    val tick: StateFlow<ClockTick?> = _tick.asStateFlow()

    private var job: Job? = null

    /** Start the minute ticker once; repeated calls (e.g. on rotation) are ignored. */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        clockLog.add("LiveClock start: waiting for next boundary")
        Log.i(TAG, "LiveClock.start launched (previous realign/stop), waiting for next boundary") // WATCHDOG
        job = scope.launch {
            while (isActive) {
                val season = seasons.active()
                if (season == null) {
                    // First launch: the season is created after the data import.
                    delay(1_000L)
                    continue
                }
                // Wait for the next real minute boundary BEFORE the first tick, so every tick
                // is boundary-aligned and none fires at an arbitrary off-minute instant. The
                // chart is already live from catch-up, so it does not need a tick to open.
                val nowMs = clock()
                val waitMs = delayToNextMinute(nowMs)
                clockLog.add("LiveClock schedule: next tick in ${waitMs}ms (now=${ClockLog.format(nowMs)})")
                Log.i(TAG, "LiveClock scheduling next tick in ${waitMs}ms (now=$nowMs)") // WATCHDOG
                delay(waitMs)
                // A throw here (e.g. a Room query on a torn-down DB) would otherwise end this
                // loop for good, leaving the minute ticker dead (lastTick stuck at "—"). Absorb
                // it and re-align onto the next boundary so the ticker always recovers.
                try {
                    emitTick(season)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    clockLog.add("LiveClock emitTick FAILED: ${t::class.java.simpleName}: ${t.message}")
                    Log.e(TAG, "LiveClock.emitTick failed; realigning", t) // WATCHDOG
                }
            }
        }
    }

    /**
     * Re-base the schedule onto the next real minute boundary. Call on resume: a delay that
     * was suspended while backgrounded may otherwise resume and fire a late, off-minute tick.
     * Cancelling and restarting waits for the boundary again, so the next tick is aligned.
     */
    fun realign(scope: CoroutineScope) {
        clockLog.add("LiveClock realign (cancel + restart)")
        Log.i(TAG, "LiveClock.realign (cancel + restart)") // WATCHDOG
        job?.cancel()
        job = null
        start(scope)
    }

    private suspend fun emitTick(season: Season) {
        val realNow = clock()
        val histNow = seasons.histNow(season, realNow)
        val visible = ClockEngine.lastVisibleCandleTs(histNow)
        clockLog.add(
            "LiveClock tick: real=${ClockLog.format(realNow)} hist=${ClockLog.format(histNow)} " +
                "visible=${ClockLog.format(visible)}",
        )
        Log.i(TAG, "LiveClock tick realNow=$realNow histNow=$histNow visible=$visible") // WATCHDOG
        _tick.value = ClockTick(realNow, histNow, visible)
        candles.publish(visible)
    }

    companion object {
        private const val TAG = "TradeQuest"
        const val MINUTE_MS = 60_000L

        /** A small lag past the boundary so the clock has definitely ticked over. */
        const val TICK_LAG_MS = 50L

        /** A few ms past the next real minute boundary, measured from that boundary. */
        fun delayToNextMinute(now: Long): Long =
            nextMinuteBoundary(now) - now + TICK_LAG_MS

        /**
         * The next real minute boundary at or after [now] (its seconds are 00.000). The
         * countdown and the tick delay are both derived from this, so they always equal the
         * wall clock's time to the next :00 and never depend on when a tick last fired.
         */
        fun nextMinuteBoundary(now: Long): Long {
            val remainder = Math.floorMod(now, MINUTE_MS)
            return now + (MINUTE_MS - remainder)
        }
    }
}
