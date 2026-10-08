package com.tradequest.data

import com.tradequest.engine.Impact
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Import must land exactly the rows in the asset, in batches, and skip malformed lines. */
@RunWith(RobolectricTestRunner::class)
class DatasetImporterTest {

    private val assetsDir = File("src/test/resources/assets")

    @Test
    fun importInsertsEveryValidRow() = runTest {
        val db = TestDb.open()
        val result = DatasetImporter.import(db, FileAssetSource(assetsDir), force = true)

        // 4 valid candle rows; the "not,a,valid,row" line is skipped.
        assertEquals(4L, result.candleCount)
        assertEquals(3L, result.newsCount)
        assertEquals(4, db.candleDao().count())
        assertEquals(3, db.newsDao().count())
        assertEquals(Impact.HIGH, db.newsDao().all().first().impact)
        db.close()
    }

    @Test
    fun importIsIdempotentUnlessForced() = runTest {
        val db = TestDb.open()
        DatasetImporter.import(db, FileAssetSource(assetsDir), force = true)
        DatasetImporter.import(db, FileAssetSource(assetsDir), force = false)
        assertEquals(4, db.candleDao().count())
        assertEquals(3, db.newsDao().count())
        db.close()
    }

    @Test
    fun forceImportReplacesExistingRows() = runTest {
        val db = TestDb.open()
        DatasetImporter.import(db, FileAssetSource(assetsDir), force = true)
        DatasetImporter.import(db, FileAssetSource(assetsDir), force = true)
        assertEquals(4, db.candleDao().count())
        db.close()
    }

    @Test
    fun batchProgressIsReported() = runTest {
        val db = TestDb.open()
        val phases = mutableSetOf<ImportProgress.Phase>()
        DatasetImporter.import(db, FileAssetSource(assetsDir), force = true) {
            phases.add(it.phase)
        }
        assertEquals(setOf(ImportProgress.Phase.CANDLES, ImportProgress.Phase.NEWS), phases)
        db.close()
    }

    @Test
    fun parseImpactAcceptsCommonSpellings() {
        assertEquals(Impact.HIGH, DatasetImporter.parseImpact("high"))
        assertEquals(Impact.MEDIUM, DatasetImporter.parseImpact("Med"))
        assertEquals(Impact.LOW, DatasetImporter.parseImpact("something else"))
    }
}
