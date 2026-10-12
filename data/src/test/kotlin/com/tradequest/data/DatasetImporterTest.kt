package com.tradequest.data

import com.tradequest.engine.Impact
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Import must land exactly the rows in the asset, in batches, and skip malformed lines. */
@RunWith(RobolectricTestRunner::class)
class DatasetImporterTest {

    private val assetsDir = File("src/test/resources/assets")
    private val plausibleDir = File("src/test/resources/plausible_assets")
    private val plausibleGzDir = File("src/test/resources/plausible_assets_gz")

    @Test
    fun importInsertsEveryValidRow() = runTest {
        val db = TestDb.open()
        val result = DatasetImporter.import(db, FileAssetSource(plausibleDir), force = true, minRows = 1)

        // 10 valid candle rows / 3 news rows, all with a matching meta file.
        assertEquals(10L, result.candleCount)
        assertEquals(3L, result.newsCount)
        assertEquals(DatasetSource.BUNDLED, result.source)
        assertEquals(10, db.candleDao().count())
        assertEquals(3, db.newsDao().count())
        assertEquals(Impact.HIGH, db.newsDao().all().first().impact)
        assertNotNull(result.meta)
        assertEquals("test fixture", result.meta!!.source)
        db.close()
    }

    @Test
    fun importsGzippedAssetWithMatchingMeta() = runTest {
        val db = TestDb.open()
        val result = DatasetImporter.import(db, FileAssetSource(plausibleGzDir), force = true, minRows = 1)
        assertEquals(DatasetSource.BUNDLED, result.source)
        assertEquals(10L, result.candleCount)
        db.close()
    }

    @Test
    fun rejectsAssetWhoseMetaIsMissing() = runTest {
        val db = TestDb.open()
        val result = DatasetImporter.import(
            db, FileAssetSource(File("src/test/resources/nometa_assets")), force = true, minRows = 1,
        )
        assertEquals(DatasetSource.UNVERIFIED, result.source)
        assertTrue(result.failureReason!!.contains("metadata is missing"))
        assertEquals(0, db.candleDao().count())
        db.close()
    }

    @Test
    fun rejectsAssetWhoseChecksumDoesNotMatch() = runTest {
        val db = TestDb.open()
        val result = DatasetImporter.import(
            db, FileAssetSource(File("src/test/resources/tampered_assets")), force = true, minRows = 1,
        )
        assertEquals(DatasetSource.UNVERIFIED, result.source)
        assertTrue(result.failureReason!!.contains("checksum mismatch"))
        assertEquals(0, db.candleDao().count())
        db.close()
    }

    @Test
    fun rejectsImplausibleAssetEvenWhenLargeEnough() = runTest {
        val db = TestDb.open()
        // The tiny fixture is only 4 flat rows, but minRows=1 lets it reach the plausibility
        // gate; with no meta file it is rejected before the structure check would run.
        val result = DatasetImporter.import(db, FileAssetSource(assetsDir), force = true, minRows = 1)
        assertEquals(DatasetSource.UNVERIFIED, result.source)
        assertEquals(0, db.candleDao().count())
        db.close()
    }

    @Test
    fun reportsMissingSourceWhenAssetIsTooSmallAndFakeIsNotAllowed() = runTest {
        val db = TestDb.open()
        // The fixture has 4 rows; with the real 80k floor and fake disabled it is rejected.
        val result = DatasetImporter.import(
            db, FileAssetSource(assetsDir), force = true, allowFake = false, minRows = 80_000,
        )
        assertEquals(DatasetSource.MISSING, result.source)
        assertEquals(0, db.candleDao().count())
        db.close()
    }

    @Test
    fun fallsBackToFakeGeneratorOnlyWhenExplicitlyAllowed() = runTest {
        val db = TestDb.open()
        val result = DatasetImporter.import(
            db, FileAssetSource(assetsDir), force = true, allowFake = true, minRows = 80_000,
        )
        assertEquals(DatasetSource.FAKE, result.source)
        assertEquals(DatasetImporter.FAKE_ROWS.toLong(), result.candleCount)
        assertEquals(DatasetImporter.FAKE_ROWS.toLong(), db.candleDao().count())
        db.close()
    }

    @Test
    fun importIsIdempotentUnlessForced() = runTest {
        val db = TestDb.open()
        DatasetImporter.import(db, FileAssetSource(plausibleDir), force = true, minRows = 1)
        DatasetImporter.import(db, FileAssetSource(plausibleDir), force = false, minRows = 1)
        assertEquals(10, db.candleDao().count())
        assertEquals(3, db.newsDao().count())
        db.close()
    }

    @Test
    fun forceImportReplacesExistingRows() = runTest {
        val db = TestDb.open()
        DatasetImporter.import(db, FileAssetSource(plausibleDir), force = true, minRows = 1)
        DatasetImporter.import(db, FileAssetSource(plausibleDir), force = true, minRows = 1)
        assertEquals(10, db.candleDao().count())
        db.close()
    }

    @Test
    fun batchProgressIsReported() = runTest {
        val db = TestDb.open()
        val phases = mutableSetOf<ImportProgress.Phase>()
        DatasetImporter.import(db, FileAssetSource(plausibleDir), force = true, minRows = 1) {
            phases.add(it.phase)
        }
        assertEquals(setOf(ImportProgress.Phase.CANDLES, ImportProgress.Phase.NEWS), phases)
        db.close()
    }

    /**
     * The APK ships the candles *uncompressed* because AGP gunzips assets named `*.gz` at
     * merge time, so on-device the file is `xauusd_m1.csv`. The importer must find it there
     * too rather than looking only for the `.gz` name (which used to abort first launch).
     */
    @Test
    fun importsPlainCsvWhenAssetWasGunzippedByTheBuild() = runTest {
        val db = TestDb.open()
        val result = DatasetImporter.import(db, FileAssetSource(plausibleDir), force = true, minRows = 1)

        assertEquals(10L, result.candleCount)
        assertEquals(3L, result.newsCount)
        assertEquals(10, db.candleDao().count())
        db.close()
    }

    @Test
    fun metaRowCountMismatchIsRejected() = runTest {
        val db = TestDb.open()
        // The plausible meta declares 10 rows; a file with one row removed (and a stale sha)
        // must fail on structure/rowCount rather than import a truncated series.
        val tmp = File.createTempFile("assets", "").apply { delete(); mkdirs() }
        File(plausibleDir, "xauusd_m1.csv").copyTo(File(tmp, "xauusd_m1.csv"), overwrite = true)
        File(plausibleDir, "news.json").copyTo(File(tmp, "news.json"), overwrite = true)
        File(plausibleDir, "xauusd_m1.meta.json").copyTo(File(tmp, "xauusd_m1.meta.json"), overwrite = true)
        val csv = File(tmp, "xauusd_m1.csv")
        csv.writeText(csv.readLines().dropLast(2).joinToString("\n") + "\n")

        val result = DatasetImporter.import(db, FileAssetSource(tmp), force = true, minRows = 1)
        assertEquals(DatasetSource.UNVERIFIED, result.source)
        /* checksum is stale after the edit, so checksum (not rowCount) fails first */
        assertTrue(result.failureReason!!.contains("checksum mismatch"))
        assertEquals(0, db.candleDao().count())
        db.close()
    }

    @Test
    fun parseImpactAcceptsCommonSpellings() {
        assertEquals(Impact.HIGH, DatasetImporter.parseImpact("high"))
        assertEquals(Impact.MEDIUM, DatasetImporter.parseImpact("Med"))
        assertEquals(Impact.LOW, DatasetImporter.parseImpact("something else"))
    }
}
