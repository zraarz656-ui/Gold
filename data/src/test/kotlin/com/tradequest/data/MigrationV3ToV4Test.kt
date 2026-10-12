package com.tradequest.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v3 -> v4 migrates `trade_order` to AUTOINCREMENT ids with `parentPositionId`, remapping the
 * old negative-id partial-close rows into the dedicated closed-row id range. The migration must
 * preserve every row and every field, and Room must validate the resulting schema.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationV3ToV4Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TradeQuestDatabase::class.java,
        emptyList(),
    )

    private fun insertV3TradeOrder(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: Long,
        status: String,
        side: String,
        lots: Double,
        closedAt: Long?,
        closeReason: String?,
        closePrice: Double?,
        pnl: Double?,
        seasonId: Long = 1L,
    ) {
        db.execSQL(
            "INSERT INTO trade_order (id, seasonId, type, side, lots, entryPrice, sl, tp, " +
                "trailingDist, status, openedAt, closedAt, closePrice, closeReason, triggerPrice, " +
                "grossPnl, pnl, fees, tag, note, pendingTrail) VALUES " +
                "($id, $seasonId, 'MARKET', '$side', $lots, 2400.0, 2390.0, 2410.0, NULL, '$status', " +
                "1000, ${closedAt ?: "NULL"}, ${closePrice ?: "NULL"}, ${closeReason?.let { "'$it'" } ?: "NULL"}, " +
                "NULL, NULL, ${pnl ?: "NULL"}, 0.7, NULL, NULL, NULL)",
        )
    }

    @Test
    fun migratesNegativePartialCloseRowsToDedicatedPositiveIdsAndPreservesData() {
        helper.createDatabase(TradeQuestDatabase.NAME, 3).use { db ->
            // The surviving position (positive id, still open) and the pending order.
            insertV3TradeOrder(db, id = 1L, status = "OPEN", side = "LONG", lots = 0.05, closedAt = null, closeReason = null, closePrice = null, pnl = null)
            insertV3TradeOrder(db, id = 2L, status = "PENDING", side = "LONG", lots = 0.10, closedAt = null, closeReason = null, closePrice = null, pnl = null)
            // A full close reusing the position id.
            insertV3TradeOrder(db, id = 3L, status = "CLOSED", side = "LONG", lots = 0.10, closedAt = 1500, closeReason = "TP", closePrice = 2410.0, pnl = 99.30)
            // Two legacy negative-id partial-close rows for position 1.
            insertV3TradeOrder(db, id = -1L, status = "CLOSED", side = "LONG", lots = 0.05, closedAt = 1100, closeReason = "PARTIAL", closePrice = 2405.0, pnl = 24.65)
            insertV3TradeOrder(db, id = -2L, status = "CLOSED", side = "LONG", lots = 0.05, closedAt = 1200, closeReason = "PARTIAL", closePrice = 2406.0, pnl = 29.65)
            db.close()
        }

        // Reopen at v4, validating the migration against the exported schema 4.json.
        val db = helper.runMigrationsAndValidate(TradeQuestDatabase.NAME, 4, true, *TradeQuestDatabase.MIGRATIONS)

        val rows = db.query("SELECT * FROM trade_order ORDER BY id").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Row(
                            id = c.getLong(c.getColumnIndexOrThrow("id")),
                            status = c.getString(c.getColumnIndexOrThrow("status")),
                            side = c.getString(c.getColumnIndexOrThrow("side")),
                            lots = c.getDouble(c.getColumnIndexOrThrow("lots")),
                            closeReason = c.getString(c.getColumnIndexOrThrow("closeReason")),
                            closePrice = if (c.isNull(c.getColumnIndexOrThrow("closePrice"))) null else c.getDouble(c.getColumnIndexOrThrow("closePrice")),
                            parent = if (c.isNull(c.getColumnIndexOrThrow("parentPositionId"))) null else c.getLong(c.getColumnIndexOrThrow("parentPositionId")),
                        ),
                    )
                }
            }
        }

        assertEquals("no row lost", 5, rows.size)

        val byId = rows.associateBy { it.id }
        // Positive ids are untouched.
        assertEquals("OPEN", byId[1L]!!.status)
        assertEquals("PENDING", byId[2L]!!.status)
        assertEquals("TP", byId[3L]!!.closeReason)
        assertNull("a full close has no parent", byId[3L]!!.parent)

        // Legacy negative rows moved into the closed-row range. Their parent is unknown
        // (the old sentinel was a sequence, not a position id), so it stays null.
        assertTrue("no negative ids remain", rows.all { it.id > 0L })
        val partials = rows.filter { it.closeReason == "PARTIAL" }
        assertEquals(2, partials.size)
        assertTrue("partial ids are in the dedicated range", partials.all { it.id >= TradeQuestDatabase.CLOSED_ROW_ID_BASE })
        assertTrue("partial ids are distinct", partials.map { it.id }.toSet().size == 2)
        assertTrue("a legacy partial row cannot claim a parent", partials.all { it.parent == null })
        // Fields survive the rebuild.
        val firstByClose = partials.sortedBy { it.closePrice }
        assertEquals(2405.0, firstByClose[0].closePrice!!, 1e-9)
        assertEquals(2406.0, firstByClose[1].closePrice!!, 1e-9)
        assertEquals(0.05, firstByClose[0].lots, 1e-9)
        db.close()
    }

    private data class Row(
        val id: Long,
        val status: String,
        val side: String,
        val lots: Double,
        val closeReason: String?,
        val closePrice: Double?,
        val parent: Long?,
    )
}
