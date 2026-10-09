package com.tradequest.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

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
    version = 2,
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

        fun build(context: Context): TradeQuestDatabase =
            Room.databaseBuilder(context, TradeQuestDatabase::class.java, NAME)
                .fallbackToDestructiveMigration()
                .build()
    }
}
