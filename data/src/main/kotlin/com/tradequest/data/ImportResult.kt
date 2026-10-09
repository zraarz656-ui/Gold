package com.tradequest.data

/** Progress reported while importing bundled data. */
data class ImportProgress(
    val phase: Phase,
    val done: Int,
    val total: Int,
) {
    enum class Phase { CANDLES, NEWS }
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/** Summary of a completed import. */
data class ImportResult(
    val candleCount: Long,
    val newsCount: Long,
    val datasetStartMs: Long,
    val datasetEndMs: Long,
    val source: DatasetSource = DatasetSource.BUNDLED,
    val assetName: String? = null,
    val assetRows: Long = 0L,
    /** True when a forced re-import moved the dataset range and flagged the season. */
    val rangeChanged: Boolean = false,
)
