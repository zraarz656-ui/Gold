package com.tradequest.app.di

import android.content.Context
import com.tradequest.data.AndroidAssetSource
import com.tradequest.data.AssetSource
import com.tradequest.data.CandleDao
import com.tradequest.data.CandleRepository
import com.tradequest.data.CatchUpProcessor
import com.tradequest.data.DailyStatsDao
import com.tradequest.data.EquitySnapshotDao
import com.tradequest.data.NewsDao
import com.tradequest.data.PreferencesStore
import com.tradequest.data.SeasonRepository
import com.tradequest.data.SettingsDao
import com.tradequest.data.SettingsRepository
import com.tradequest.data.TradeOrderDao
import com.tradequest.data.TradeQuestDatabase
import com.tradequest.data.TradingRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): TradeQuestDatabase =
        TradeQuestDatabase.build(context)

    @Provides fun candleDao(db: TradeQuestDatabase): CandleDao = db.candleDao()
    @Provides fun newsDao(db: TradeQuestDatabase): NewsDao = db.newsDao()
    @Provides fun tradeOrderDao(db: TradeQuestDatabase): TradeOrderDao = db.tradeOrderDao()
    @Provides fun dailyStatsDao(db: TradeQuestDatabase): DailyStatsDao = db.dailyStatsDao()
    @Provides fun equitySnapshotDao(db: TradeQuestDatabase): EquitySnapshotDao = db.equitySnapshotDao()
    @Provides fun settingsDao(db: TradeQuestDatabase): SettingsDao = db.settingsDao()

    @Provides
    @Singleton
    fun assetSource(@ApplicationContext context: Context): AssetSource = AndroidAssetSource(context)

    @Provides
    @Singleton
    fun candleRepository(db: TradeQuestDatabase): CandleRepository = CandleRepository(db)

    @Provides
    @Singleton
    fun seasonRepository(db: TradeQuestDatabase): SeasonRepository = SeasonRepository(db)

    @Provides
    @Singleton
    fun tradingRepository(db: TradeQuestDatabase): TradingRepository = TradingRepository(db)

    @Provides
    @Singleton
    fun settingsRepository(db: TradeQuestDatabase): SettingsRepository = SettingsRepository(db)

    @Provides
    @Singleton
    fun preferencesStore(@ApplicationContext context: Context): PreferencesStore = PreferencesStore(context)

    @Provides
    @Singleton
    fun catchUpProcessor(db: TradeQuestDatabase): CatchUpProcessor = CatchUpProcessor(db)
}
