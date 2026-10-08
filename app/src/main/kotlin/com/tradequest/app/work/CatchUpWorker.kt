package com.tradequest.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tradequest.data.CatchUpProcessor
import com.tradequest.data.SeasonRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic (15 min) catch-up.
 *
 * WorkManager timing is approximate, so this is only a best-effort notifier: the
 * authoritative catch-up runs when the app is opened or resumed.
 */
@HiltWorker
class CatchUpWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val seasons: SeasonRepository,
    private val catchUp: CatchUpProcessor,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val season = seasons.active() ?: return Result.success()
        val histNow = seasons.histNow(season)
        val result = catchUp.run(season.id, histNow)
        Notifier.postCatchUp(applicationContext, result)
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "tradequest-catchup"
    }
}
