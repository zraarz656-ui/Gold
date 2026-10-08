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

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun import(
        db: TradeQuestDatabase,
        assets: AssetSource,
        force: Boolean = false,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportResult = withContext(Dispatchers.IO) {
        if (!force && db.candleDao().count() > 0L) {
            return@withContext summary(db)
        }

        if (force) {
            db.candleDao().deleteAll()
            db.newsDao().deleteAll()
        }

        importCandles(db, assets, onProgress)
        importNews(db, assets, onProgress)
        summary(db)
    }

    private suspend fun importCandles(
        db: TradeQuestDatabase,
        assets: AssetSource,
        onProgress: (ImportProgress) -> Unit,
    ) {
        val stream = gzipOrPlain(assets.open(CANDLE_ASSET))
        val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
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
        val text = assets.open(NEWS_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
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

    private suspend fun summary(db: TradeQuestDatabase): ImportResult {
        val start = db.candleDao().minTs() ?: 0L
        val end = db.candleDao().maxTs() ?: 0L
        return ImportResult(db.candleDao().count(), db.newsDao().count(), start, end)
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

    private fun gzipOrPlain(stream: InputStream): InputStream {
        val buffered = stream.buffered(64 * 1024)
        buffered.mark(2)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(buffered) else buffered
    }
}
