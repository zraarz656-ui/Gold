package com.tradequest.chart

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Owns the [ChartController] and the live replay ticker.
 *
 * In Phase 2 the ticker walked a synthetic candle every [liveIntervalMs]. Phase 3 replaces
 * [liveLoop] with candles produced by replaying the bundled dataset on the historical clock.
 */
class ChartViewModel(
    private val count: Int = DEFAULT_COUNT,
    private val liveIntervalMs: Long = 1_000L,
) : ViewModel() {

    var controller by mutableStateOf<ChartController?>(null)
        private set

    var liveRunning by mutableStateOf(false)
        private set

    val gestureDebug = GestureDebug()

    private var liveJob: Job? = null

    init {
        viewModelScope.launch {
            val data = withContext(Dispatchers.Default) {
                FakeCandleGenerator.generateWithNews(count)
            }
            controller = ChartController(data.candles, data.news)
        }
    }

    fun toggleLive() {
        val job = liveJob
        if (job != null && job.isActive) {
            job.cancel()
            liveJob = null
            liveRunning = false
            return
        }
        val c = controller ?: return
        liveRunning = true
        liveJob = viewModelScope.launch(Dispatchers.Default) { liveLoop(c) }
    }

    private suspend fun liveLoop(c: ChartController) {
        val rnd = Random(7)
        var last = c.state.m1.lastOrNull()
        var clock = c.state.clockMs ?: last?.ts ?: 0L
        while (currentCoroutineContext().isActive) {
            delay(liveIntervalMs)
            val next = LiveCandleWalk.next(last, rnd)
            clock += 60_000L
            withContext(Dispatchers.Main) { c.appendM1(next, clock) }
            last = next
        }
    }

    override fun onCleared() {
        liveJob?.cancel()
        super.onCleared()
    }

    companion object {
        const val DEFAULT_COUNT = 90_000
    }
}
