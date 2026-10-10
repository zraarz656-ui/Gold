package com.tradequest.app.clock

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/**
 * Collects [this] and, if the collector body throws, reports the failure to [onError] and
 * re-subscribes instead of letting the exception cancel the parent coroutine for good.
 *
 * A plain `collect` on a hot [kotlinx.coroutines.flow.StateFlow] shares the caller's job: one
 * throw from the per-tick work cancels that job, so the flow stops delivering for the rest of
 * the process even though the ticker keeps publishing. This keeps the subscription alive so
 * the next tick is delivered after a transient failure. The caller logs the throwable in
 * [onError] (this helper stays free of Android APIs so it can be unit tested on the JVM).
 *
 * [CancellationException] is rethrown (the collector was deliberately cancelled, e.g. the
 * ViewModel cleared); every other throwable is reported to [onError] and retried after
 * [restartDelayMs].
 */
suspend fun <T> Flow<T>.collectRestarting(
    restartDelayMs: Long,
    onError: (Throwable) -> Unit,
    action: suspend (T) -> Unit,
) {
    while (true) {
        try {
            collect { action(it) }
            return // Upstream completed normally.
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            onError(t)
            delay(restartDelayMs)
        }
    }
}
