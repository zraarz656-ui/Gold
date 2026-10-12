package com.tradequest.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Candle1m::class,
        NewsEntity::class,
        Season::class,
        TradeOrder::class,
        DailyStats::class,
        EquitySnapshotEntity::class,
        SettingEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class TradeQuestDatabase : RoomDatabase() {
    abstract fun candleDao(): CandleDao
    abstract fun newsDao(): NewsDao
    abstract fun seasonDao(): SeasonDao
    abstract fun tradeOrderDao(): TradeOrderDao
    abstract fun dailyStatsDao(): DailyStatsDao
    abstract fun equitySnapshotDao(): EquitySnapshotDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        const val NAME = "tradequest.db"

        /**
         * Closed rows that must coexist with a still-open position (partial closes) get ids
         * from a dedicated closed-row sequence. The engine allocates small positive position
         * ids (1, 2, ...), so the closed-row sequence lives far above them and can never
         * collide. The next id is `max(MAX(id) among closed-range rows, base) + 1`.
         */
        const val CLOSED_ROW_ID_BASE = 1_000_000_000L

        /** v2 -> v3: closed trades gained a close reason and a trigger price. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trade_order ADD COLUMN closeReason TEXT")
                db.execSQL("ALTER TABLE trade_order ADD COLUMN triggerPrice REAL")
                db.execSQL("ALTER TABLE trade_order ADD COLUMN grossPnl REAL")
            }
        }

        /**
         * v3 -> v4: `trade_order` gained `parentPositionId`, and partial closes no longer use
         * negative sentinel ids. `trade_order.id` changes from a plain INTEGER primary key to
         * AUTOINCREMENT, so the table is rebuilt. Existing negative ids (the old partial-close
         * rows) are remapped to fresh, positive AUTOINCREMENT ids and linked back to their
         * position via `parentPositionId`; every positive row (positions, orders, full closes)
         * keeps its id and gets a null parent. No data is lost.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            // Must match the v4 schema Room generates for `trade_order`, in field order.
            private val CREATE = "CREATE TABLE IF NOT EXISTS `trade_order_new` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `seasonId` INTEGER NOT NULL, " +
                "`type` TEXT NOT NULL, `side` TEXT NOT NULL, `lots` REAL NOT NULL, `entryPrice` REAL, " +
                "`sl` REAL, `tp` REAL, `trailingDist` REAL, `status` TEXT NOT NULL, `openedAt` INTEGER, " +
                "`closedAt` INTEGER, `closePrice` REAL, `closeReason` TEXT, `triggerPrice` REAL, " +
                "`grossPnl` REAL, `pnl` REAL, `fees` REAL, `tag` TEXT, `note` TEXT, `pendingTrail` REAL, " +
                "`parentPositionId` INTEGER)"
            private val COLS = "`seasonId`, `type`, `side`, `lots`, `entryPrice`, `sl`, `tp`, " +
                "`trailingDist`, `status`, `openedAt`, `closedAt`, `closePrice`, `closeReason`, " +
                "`triggerPrice`, `grossPnl`, `pnl`, `fees`, `tag`, `note`, `pendingTrail`"

            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE)
                // Keep positive ids exactly as they are (positions and orders must be addressable).
                db.execSQL("INSERT INTO `trade_order_new` (`id`, $COLS) SELECT `id`, $COLS FROM `trade_order` WHERE `id` >= 0")
                // Legacy negative-id closed rows move into the dedicated closed-row range. The old
                // sentinel was `-(closedCount + 1)` — a plain sequence, not the parent position id —
                // so the parent is unknown and stays NULL. Every other field is preserved.
                val negatives = "SELECT * FROM `trade_order` WHERE `id` < 0"
                db.execSQL(
                    "INSERT INTO `trade_order_new` (`id`, $COLS) " +
                        "SELECT $CLOSED_ROW_ID_BASE + ROW_NUMBER() OVER (ORDER BY `closedAt`, `id`) - 1, " +
                        "$COLS FROM ($negatives)",
                )
                db.execSQL("DROP TABLE `trade_order`")
                db.execSQL("ALTER TABLE `trade_order_new` RENAME TO `trade_order`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trade_order_seasonId` ON `trade_order` (`seasonId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trade_order_status` ON `trade_order` (`status`)")
            }
        }

        /** The full migration chain, in order, for [Room] and for migration tests. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_2_3, MIGRATION_3_4)

        fun build(context: Context): TradeQuestDatabase =
            Room.databaseBuilder(context, TradeQuestDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .fallbackToDestructiveMigration()
                .build()
    }
}
