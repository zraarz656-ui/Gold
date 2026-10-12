package com.tradequest.data

import com.tradequest.engine.Candle

/** Result of verifying the bundled dataset against its meta file and structure. */
sealed interface DatasetCheck {
    data class Pass(val meta: MetaFile) : DatasetCheck

    /** The dataset must not be trusted; [reason] is shown verbatim by the app. */
    data class Fail(val reason: String) : DatasetCheck
}

/**
 * Verifies the candle asset end to end: the meta file must exist, its sha256 and rowCount
 * must match the file actually bundled, and the parsed series must pass the structural
 * plausibility checks. A generated placeholder has no (or a non-matching) meta file, so it
 * is rejected before any candle reaches Room.
 */
object DatasetVerifier {

    /**
     * @param sha256Hex SHA-256 of the asset's canonical CSV content, computed by the caller
     *   while it reads the file (see [DatasetImporter]).
     * @param candles the parsed rows, used for the row-count and structure checks.
     */
    fun verify(assets: AssetSource, sha256Hex: String, candles: List<Candle>): DatasetCheck {
        val meta = DatasetMeta.read(assets)
            ?: return DatasetCheck.Fail(
                "bundled dataset metadata is missing or unreadable " +
                    "(assets/${DatasetMeta.META_ASSET})",
            )

        if (!sha256Hex.equals(meta.sha256, ignoreCase = true)) {
            return DatasetCheck.Fail(
                "dataset checksum mismatch: meta expects ${meta.sha256}, asset is $sha256Hex",
            )
        }

        if (meta.rowCount != candles.size.toLong()) {
            return DatasetCheck.Fail(
                "dataset row count mismatch: meta says ${meta.rowCount}, asset has ${candles.size}",
            )
        }

        return when (val p = DatasetPlausibility.check(candles)) {
            is PlausibilityResult.Fail -> DatasetCheck.Fail("implausible dataset: ${p.reason}")
            PlausibilityResult.Pass -> DatasetCheck.Pass(meta)
        }
    }
}
