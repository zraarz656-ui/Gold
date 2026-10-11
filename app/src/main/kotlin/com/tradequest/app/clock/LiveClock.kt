package com.tradequest.app.clock

import com.tradequest.data.CandleRepository
import com.tradequest.data.Season
import com.tradequest.data.SeasonRepository
import com.tradequest.engine.ClockEngine
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
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _tick = MutableStateFlow<ClockTick?>(null)
    val tick: StateFlow<ClockTick?> = _tick.asStateFlow()

    private var job: Job? = null

    /** Start the minute ticker once; repeated calls (e.g. on rotation) are ignored. */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
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
                delay(delayToNextMinute(clock()))
                emitTick(season)
            }
        }
    }

    /**
     * Re-base the schedule onto the next real minute boundary. Call on resume: a delay that
     * was suspended while backgrounded may otherwise resume and fire a late, off-minute tick.
     * Cancelling and restarting waits for the boundary again, so the next tick is aligned.
     */
    fun realign(scope: CoroutineScope) {
        job?.cancel()
        job = null
        start(scope)
    }

    private suspend fun emitTick(season: Season) {
        val realNow = clock()
        val histNow = seasons.histNow(season, realNow)
        val visible = ClockEngine.lastVisibleCandleTs(histNow)
        _tick.value = ClockTick(realNow, histNow, visible)
        candles.publish(visible)
    }

    companion object {
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
