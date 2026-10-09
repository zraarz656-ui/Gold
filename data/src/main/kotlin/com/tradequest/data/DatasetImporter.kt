package com.tradequest.data

import com.tradequest.engine.Impact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/** Shape of the bundled news JSON. */
@Serializable
data class NewsFile(val events: List<NewsItem>)

@Serializable
data class NewsItem(val ts: Long, val title: String, val impact: String)

/**
 * Imports the bundled dataset into Room.
 *
 * The candle file is a gzipped CSV with `ts,o,h,l,c,v` per line (UTC epoch ms), the same
 * shape `tools/prep-data.js` writes. Rows are inserted in batches so a large file does not
 * need to be held in memory, and progress is reported per batch.
 */
object DatasetImporter {
    const val CANDLE_ASSET = "xauusd_m1.csv.gz"
    const val NEWS_ASSET = "news.json"
    const val BATCH_SIZE = 5_000

    /** Lower bound the bundled asset must clear before it is trusted as real history. */
    const val MIN_EXPECTED_ROWS = 80_000L

    /** Where a bundled file normally lives once AGP gunzips the `.gz` name away. */
    const val CANDLE_ASSET_PLAIN = "xauusd_m1.csv"

    /** Candle rows the debug fake fallback writes (when the asset is missing or tiny). */
    const val FAKE_ROWS = 90_000

    /** How many weeks of trailing time the fake fallback covers. */
    const val FAKE_WEEKS_BACK = 14L

    /** True when a usable bundled candle asset is present. */
    fun bundledCandleAsset(assets: AssetSource): String? =
        CANDLE_CANDIDATES.firstOrNull { assets.exists(it) }

    /** True when the bundled news asset is present. */
    fun bundledNewsAsset(assets: AssetSource): Boolean = assets.exists(NEWS_ASSET)

    /**
     * Asset names to try for the candles, in order.
     *
     * The Android Gradle plugin transparently gunzips an asset whose name ends in `.gz`
     * while merging it into the APK, so on-device the file is `xauusd_m1.csv` even though
     * the source (and the test resources) are `xauusd_m1.csv.gz`. Resolving both names and
     * sniffing the gzip magic keeps the same source working in tests and in the app.
     */
    private val CANDLE_CANDIDATES = listOf(CANDLE_ASSET, CANDLE_ASSET_PLAIN)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Import the bundled dataset.
     *
     * [allowFake] is a **test-only** escape hatch: it lets a test drive the deterministic
     * fake generator when the tiny fixture asset cannot clear [minRows]. Production callers
     * never pass it, so a missing or undersized bundled asset always yields
     * [DatasetSource.MISSING] and the app shows the data-error screen instead of replaying
     * generated placeholder data.
     */
    suspend fun import(
        db: TradeQuestDatabase,
        assets: AssetSource,
        force: Boolean = false,
        allowFake: Boolean = false,
        minRows: Long = MIN_EXPECTED_ROWS,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportResult = withContext(Dispatchers.IO) {
        if (!force && db.candleDao().count() > 0L) {
            return@withContext summary(db, DatasetSource.fromId(
                db.settingsDao().get(SettingsRepository.DATA_SOURCE),
            ) ?: DatasetSource.BUNDLED)
        }

        val assetName = bundledCandleAsset(assets)
        val assetRows = assetName?.let { countAssetRows(assets, it) } ?: 0L
        val assetUsable = assetRows >= minRows

        // A forced import replaces only candle_1m and news_event; the range is captured so a
        // change can flag the season (its clock offset depends on the dataset start).
        var beforeStart = 0L
        var beforeEnd = 0L
        if (force) {
            beforeStart = db.candleDao().minTs() ?: 0L
            beforeEnd = db.candleDao().maxTs() ?: 0L
        }

        if (!assetUsable && !allowFake) {
            // No usable bundled history: report it rather than quietly replaying a fake.
            if (force) {
                db.candleDao().deleteAll()
                db.newsDao().deleteAll()
            }
            return@withContext ImportResult(
                candleCount = db.candleDao().count(),
                newsCount = db.newsDao().count(),
                datasetStartMs = db.candleDao().minTs() ?: 0L,
                datasetEndMs = db.candleDao().maxTs() ?: 0L,
                source = DatasetSource.MISSING,
                assetName = assetName,
                assetRows = assetRows,
            )
        }

        if (force) {
            db.candleDao().deleteAll()
            db.newsDao().deleteAll()
        }

        val source: DatasetSource
        if (assetUsable) {
            val name = assetName!!
            importCandles(db, assets, name, onProgress)
            source = DatasetSource.BUNDLED
        } else {
            importFake(db, onProgress)
            source = DatasetSource.FAKE
        }
        importNews(db, assets, onProgress)

        // Only the two data tables were rewritten. If the dataset's range moved, the season's
        // fixed offset no longer points at the same window, so flag every existing season to
        // be reset; trades, stats and snapshots are preserved until the user acts on it.
        var rangeChanged = false
        if (force && beforeStart != 0L) {
            val afterStart = db.candleDao().minTs() ?: 0L
            val afterEnd = db.candleDao().maxTs() ?: 0L
            if (afterStart != beforeStart || afterEnd != beforeEnd) {
                db.seasonDao().flagAllForReset()
                rangeChanged = true
            }
        }

        summary(db, source, assetName, assetRows).copy(rangeChanged = rangeChanged)
    }

    /** Count candle rows in the asset without importing them. */
    private suspend fun countAssetRows(assets: AssetSource, name: String): Long = withContext(Dispatchers.IO) {
        val raw = assets.open(name)
        BufferedReader(InputStreamReader(gzipOrPlain(raw), Charsets.UTF_8)).useLines { lines ->
            lines.count { parseCandle(it) != null }
        }.toLong()
    }

    /** Write the deterministic fake series into Room when no usable asset is bundled. */
    private suspend fun importFake(
        db: TradeQuestDatabase,
        onProgress: (ImportProgress) -> Unit,
    ) {
        onProgress(ImportProgress(ImportProgress.Phase.CANDLES, 0, 0))
        val generated = FakeCandles.generateWithNews(
            count = FAKE_ROWS,
            startMs = FakeCandles.startFor(System.currentTimeMillis(), weeksBack = FAKE_WEEKS_BACK),
        )
        val candles = generated.candles.map {
            Candle1m(it.ts, it.o, it.h, it.l, it.c, it.v)
        }
        var done = 0
        candles.chunked(BATCH_SIZE).forEach { chunk ->
            db.candleDao().insertAll(chunk)
            done += chunk.size
            onProgress(ImportProgress(ImportProgress.Phase.CANDLES, done, candles.size))
        }
        db.newsDao().insertAll(
            generated.news.map { NewsEntity(ts = it.ts, title = it.title, impact = it.impact) },
        )
        onProgress(ImportProgress(ImportProgress.Phase.CANDLES, done, done))
    }

    private suspend fun importCandles(
        db: TradeQuestDatabase,
        assets: AssetSource,
        assetName: String,
        onProgress: (ImportProgress) -> Unit,
    ) {
        val raw = assets.open(assetName)
        val reader = BufferedReader(InputStreamReader(gzipOrPlain(raw), Charsets.UTF_8))
        var batch = ArrayList<Candle1m>(BATCH_SIZE)
        var done = 0
        onProgress(ImportProgress(ImportProgress.Phase.CANDLES, 0, 0))
        reader.useLines { lines ->
            for (line in lines) {
                val row = parseCandle(line) ?: continue
                batch.add(row)
                if (batch.size >= BATCH_SIZE) {
                    db.candleDao().insertAll(batch)
                    done += batch.size
                    batch = ArrayList(BATCH_SIZE)
                    onProgress(ImportProgress(ImportProgress.Phase.CANDLES, done, 0))
                }
            }
        }
        if (batch.isNotEmpty()) {
            db.candleDao().insertAll(batch)
            done += batch.size
        }
        onProgress(ImportProgress(ImportProgress.Phase.CANDLES, done, done))
    }

    private suspend fun importNews(
        db: TradeQuestDatabase,
        assets: AssetSource,
        onProgress: (ImportProgress) -> Unit,
    ) {
        val text = openFirst(assets, listOf(NEWS_ASSET)).use { it.readBytes().toString(Charsets.UTF_8) }
        val file = json.decodeFromString(NewsFile.serializer(), text)
        val rows = file.events.map {
            NewsEntity(ts = it.ts, title = it.title, impact = parseImpact(it.impact))
        }
        rows.chunked(BATCH_SIZE).forEach { chunk ->
            db.newsDao().insertAll(chunk)
            onProgress(ImportProgress(ImportProgress.Phase.NEWS, chunk.size, rows.size))
        }
        onProgress(ImportProgress(ImportProgress.Phase.NEWS, rows.size, rows.size))
    }

    private suspend fun summary(
        db: TradeQuestDatabase,
        source: DatasetSource,
        assetName: String? = null,
        assetRows: Long = 0L,
    ): ImportResult {
        val start = db.candleDao().minTs() ?: 0L
        val end = db.candleDao().maxTs() ?: 0L
        return ImportResult(
            candleCount = db.candleDao().count(),
            newsCount = db.newsDao().count(),
            datasetStartMs = start,
            datasetEndMs = end,
            source = source,
            assetName = assetName,
            assetRows = assetRows,
        )
    }

    internal fun parseCandle(line: String): Candle1m? {
        if (line.isBlank()) return null
        val parts = line.split(',')
        if (parts.size < 6) return null
        val ts = parts[0].trim().toLongOrNull() ?: return null
        val o = parts[1].trim().toDoubleOrNull() ?: return null
        val h = parts[2].trim().toDoubleOrNull() ?: return null
        val l = parts[3].trim().toDoubleOrNull() ?: return null
        val c = parts[4].trim().toDoubleOrNull() ?: return null
        val v = parts[5].trim().toDoubleOrNull() ?: 0.0
        return Candle1m(ts, o, h, l, c, v)
    }

    internal fun parseImpact(value: String): Impact = when (value.trim().uppercase()) {
        "HIGH" -> Impact.HIGH
        "MEDIUM", "MED" -> Impact.MEDIUM
        else -> Impact.LOW
    }

    /** Open the first asset name that exists; the last one is opened regardless so the
     *  thrown error names a real path. */
    private fun openFirst(assets: AssetSource, names: List<String>): InputStream {
        for (name in names.dropLast(1)) {
            runCatching { return assets.open(name) }
        }
        return assets.open(names.last())
    }

    private fun gzipOrPlain(stream: InputStream): InputStream {
        val buffered = stream.buffered(64 * 1024)
        buffered.mark(2)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(buffered) else buffered
    }
}
