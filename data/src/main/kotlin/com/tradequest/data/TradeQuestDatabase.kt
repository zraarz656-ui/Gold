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
    version = 3,
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

        /** v2 -> v3: closed trades gained a close reason and a trigger price. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trade_order ADD COLUMN closeReason TEXT")
                db.execSQL("ALTER TABLE trade_order ADD COLUMN triggerPrice REAL")
                db.execSQL("ALTER TABLE trade_order ADD COLUMN grossPnl REAL")
            }
        }

        fun build(context: Context): TradeQuestDatabase =
            Room.databaseBuilder(context, TradeQuestDatabase::class.java, NAME)
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
    }
}
