package com.tradequest.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Provenance for the bundled candle asset, written by `tools/prep-data.js` and shipped
 * beside the CSV. The app trusts the candles only when this file is present and agrees
 * with the asset (see [DatasetVerifier]); it is the only thing that tells a real download
 * apart from a generated placeholder.
 */
@Serializable
data class MetaFile(
    val source: String = "",
    val tool: String = "",
    val version: String = "",
    val fetchedAt: String = "",
    val from: String = "",
    val to: String = "",
    val rowCount: Long = 0L,
    val sha256: String = "",
)

/** Reads and parses the `xauusd_m1.meta.json` asset. */
object DatasetMeta {
    const val META_ASSET = "xauusd_m1.meta.json"

    private val json = Json { ignoreUnknownKeys = true }

    /** True when the meta asset is present. */
    fun exists(assets: AssetSource): Boolean = assets.exists(META_ASSET)

    /** The parsed meta file, or null when it is missing or malformed. */
    fun read(assets: AssetSource): MetaFile? = runCatching {
        if (!assets.exists(META_ASSET)) return null
        val text = assets.open(META_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        json.decodeFromString(MetaFile.serializer(), text)
    }.getOrNull()
}
