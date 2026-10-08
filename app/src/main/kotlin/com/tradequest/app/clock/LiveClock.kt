package com.tradequest.app.clock

import com.tradequest.data.CandleRepository
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
                val realNow = System.currentTimeMillis()
                val histNow = seasons.histNow(season, realNow)
                val visible = ClockEngine.lastVisibleCandleTs(histNow)
                _tick.value = ClockTick(realNow, histNow, visible)
                candles.publish(visible)
                delay(millisToNextMinute(realNow))
            }
        }
    }

    /** A few ms past the next real minute boundary. */
    private fun millisToNextMinute(realNow: Long): Long {
        val remainder = realNow % MINUTE_MS
        return MINUTE_MS - remainder + 50L
    }

    companion object {
        const val MINUTE_MS = 60_000L
    }
}
