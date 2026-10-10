package com.tradequest.app.clock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The minute ticker is a hot StateFlow. A plain `collect` shares the caller's job, so one
 * throw from the per-tick work cancels the subscription for good. [collectRestarting] must
 * survive that and deliver the next value.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RestartingCollectTest {

    @Test
    fun aCollectorThatThrowsOnceRestartsAndDeliversTheNextTick() = runTest {
        val ticker = MutableStateFlow(0L)
        val delivered = mutableListOf<Long>()
        var errors = 0

        val job = launch {
            ticker.collectRestarting(
                restartDelayMs = 1_000L,
                onError = { errors++ },
            ) { v ->
                if (v == 1L) throw IllegalStateException("boom on tick 1")
                if (v != 0L) delivered += v
            }
        }

        ticker.value = 1L // kills the collector body
        runCurrent()
        ticker.value = 2L // must still arrive after the restart
        advanceUntilIdle()

        assertEquals("the throw must be reported exactly once", 1, errors)
        assertTrue("the tick after the failure must be delivered", delivered.contains(2L))
        job.cancel()
    }

    @Test
    fun repeatedThrowsKeepRestartingWithoutLosingLaterTicks() = runTest {
        val ticker = MutableStateFlow(0L)
        val delivered = mutableListOf<Long>()
        var errors = 0

        val job = launch {
            ticker.collectRestarting(
                restartDelayMs = 1_000L,
                onError = { errors++ },
            ) { v ->
                if (v == 1L || v == 3L) throw IllegalStateException("boom on tick $v")
                if (v != 0L) delivered += v
            }
        }

        ticker.value = 1L
        runCurrent()
        ticker.value = 2L
        advanceUntilIdle()
        ticker.value = 3L
        runCurrent()
        ticker.value = 4L
        advanceUntilIdle()

        assertEquals(2, errors)
        assertTrue(delivered.contains(2L))
        assertTrue(delivered.contains(4L))
        job.cancel()
    }
}
