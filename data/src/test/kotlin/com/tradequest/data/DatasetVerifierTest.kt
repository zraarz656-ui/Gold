package com.tradequest.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end verification of a bundled asset: the meta file must exist and agree with the
 * file's sha256 and row count, and the structure must be plausible. Any mismatch is a
 * rejection carrying a reason the app shows verbatim.
 */
class DatasetVerifierTest {

    private val plausible = java.io.File("src/test/resources/plausible_assets")
    private val nometa = java.io.File("src/test/resources/nometa_assets")
    private val tampered = java.io.File("src/test/resources/tampered_assets")

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun readCandles(dir: java.io.File): List<com.tradequest.engine.Candle> {
        val text = java.io.File(dir, "xauusd_m1.csv").readText()
        return text.lineSequence().mapNotNull { line ->
            val p = line.split(',')
            if (p.size < 6) return@mapNotNull null
            val ts = p[0].toLongOrNull() ?: return@mapNotNull null
            val ohlc = p.subList(1, 5).mapNotNull { it.toDoubleOrNull() }
            if (ohlc.size < 4) return@mapNotNull null
            com.tradequest.engine.Candle(
                ts, ohlc[0], ohlc[1], ohlc[2], ohlc[3],
                p[5].toDoubleOrNull() ?: 0.0,
            )
        }.toList()
    }

    private fun shaOf(dir: java.io.File) = sha256(java.io.File(dir, "xauusd_m1.csv").readBytes())

    @Test
    fun passesWhenMetaMatchesAndStructureIsPlausible() {
        val assets = FileAssetSource(plausible)
        val check = DatasetVerifier.verify(assets, shaOf(plausible), readCandles(plausible))
        assertTrue(check is DatasetCheck.Pass)
        assertEquals("test fixture", (check as DatasetCheck.Pass).meta.source)
    }

    @Test
    fun failsWhenMetaFileIsMissing() {
        val assets = FileAssetSource(nometa)
        val check = DatasetVerifier.verify(assets, shaOf(nometa), readCandles(nometa))
        assertTrue(check is DatasetCheck.Fail)
        assertTrue((check as DatasetCheck.Fail).reason.contains("metadata is missing"))
    }

    @Test
    fun failsWhenChecksumDiffers() {
        val assets = FileAssetSource(tampered)
        val check = DatasetVerifier.verify(assets, shaOf(tampered), readCandles(tampered))
        assertTrue(check is DatasetCheck.Fail)
        assertTrue((check as DatasetCheck.Fail).reason.contains("checksum mismatch"))
    }

    @Test
    fun failsWhenRowCountDiffers() {
        val assets = FileAssetSource(plausible)
        val few = readCandles(plausible).dropLast(1)
        val check = DatasetVerifier.verify(assets, shaOf(plausible), few)
        assertTrue(check is DatasetCheck.Fail)
        assertTrue((check as DatasetCheck.Fail).reason.contains("row count mismatch"))
    }
}
