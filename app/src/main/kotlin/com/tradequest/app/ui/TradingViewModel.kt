package com.tradequest.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradequest.chart.ChartController
import com.tradequest.chart.ChartMarker
import com.tradequest.chart.ChartOrderLine
import com.tradequest.chart.ChartOverlayState
import com.tradequest.chart.LevelOutcome
import com.tradequest.chart.OrderLineKind
import com.tradequest.chart.PriceLabelSize
import com.tradequest.data.AccountCheckpoint
import com.tradequest.data.AssetSource
import com.tradequest.data.CandleRepository
import com.tradequest.data.CatchUpProcessor
import com.tradequest.data.DailyStatsDao
import com.tradequest.data.DatabaseIntrospector
import com.tradequest.data.DatasetImporter
import com.tradequest.data.DatasetMeta
import com.tradequest.data.DatasetSource
import com.tradequest.data.EquitySnapshotDao
import com.tradequest.data.EquitySnapshotEntity
import com.tradequest.data.ImportProgress
import com.tradequest.data.ImportResult
import com.tradequest.data.OrderRequest
import com.tradequest.data.OrderStatus
import com.tradequest.data.PreferencesStore
import com.tradequest.data.RiskCalculator
import com.tradequest.data.Season
import com.tradequest.data.SeasonRepository
import com.tradequest.data.SettingsRepository
import com.tradequest.data.TradeOrder
import com.tradequest.data.TradeQuestDatabase
import com.tradequest.data.TradingRepository
import com.tradequest.app.ui.theme.themeForId
import com.tradequest.engine.AccountState
import com.tradequest.engine.Candle
import com.tradequest.engine.ClosedPosition
import com.tradequest.engine.ClockEngine
import com.tradequest.engine.FillEngine
import com.tradequest.engine.MarketCalendar
import com.tradequest.engine.MarketTime
import com.tradequest.engine.NewsEvent
import com.tradequest.engine.OrderType
import com.tradequest.engine.Side
import com.tradequest.engine.Timeframe
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** High-level startup phase, so the UI can show import/catch-up progress. */
enum class StartupPhase { IMPORTING, CATCHING_UP, READY, ERROR }

data class StartupState(
    val phase: StartupPhase = StartupPhase.IMPORTING,
    val importFraction: Float = 0f,
    val catchUpFrom: Long = 0L,
    val catchUpTo: Long = 0L,
    val error: String? = null,
)

data class Quote(val bid: Double, val ask: Double, val spread: Double)

/**
 * Snapshot for the "Data (debug)" panel. Times are UTC epoch ms; the UI applies the
 * display offset and renders in the device zone.
 */
data class DataStats(
    val rowCount: Long = 0L,
    val firstTs: Long = 0L,
    val lastTs: Long = 0L,
    val closeFirst: Double = 0.0,
    val closeLast: Double = 0.0,
    val closeMin: Double = 0.0,
    val closeMedian: Double = 0.0,
    val closeMax: Double = 0.0,
    val newsCount: Long = 0L,
    val weekdayGapCount: Int = 0,
    val offsetMs: Long = 0L,
    val histNow: Long = 0L,
    val lastVisibleTs: Long = 0L,
    val weeksAhead: Int = 0,
    val source: DatasetSource = DatasetSource.MISSING,
    val assetName: String? = null,
    /** Provenance from the meta file, when present: source / fetchedAt / rowCount. */
    val metaSource: String? = null,
    val metaFetchedAt: String? = null,
    val metaRowCount: Long = 0L,
    /** Why the dataset was rejected, when the error screen is showing. */
    val failureReason: String? = null,
)

data class AccountStrip(
    val balance: Double,
    val equity: Double,
    val floatingPnl: Double,
    val dayPnl: Double,
    val marginLevel: Double,
    val openPositions: Int,
    val pendingOrders: Int,
)

/**
 * Wires the replayed clock, the chart and the trading layer together.
 *
 * Catch-up runs on every open/resume (the source of truth); the periodic worker is only
 * a notifier. All state lives in Room, so process death loses nothing.
 */
@HiltViewModel
class TradingViewModel @Inject constructor(
    private val db: TradeQuestDatabase,
    private val seasons: SeasonRepository,
    private val trading: TradingRepository,
    private val candles: CandleRepository,
    private val settings: SettingsRepository,
    private val preferences: PreferencesStore,
    private val catchUp: CatchUpProcessor,
    assetSource: AssetSource,
) : ViewModel() {

    val controller = ChartController(emptyList())

    private val assets: AssetSource = assetSource
    private val dailyStats: DailyStatsDao = db.dailyStatsDao()
    private val snapshots: EquitySnapshotDao = db.equitySnapshotDao()

    private val _startup = MutableStateFlow(StartupState())
    val startup: StateFlow<StartupState> = _startup.asStateFlow()

    private val _quote = MutableStateFlow(Quote(0.0, 0.0, 0.0))
    val quote: StateFlow<Quote> = _quote.asStateFlow()

    private val _strip = MutableStateFlow(AccountStrip(0.0, 0.0, 0.0, 0.0, Double.MAX_VALUE, 0, 0))
    val strip: StateFlow<AccountStrip> = _strip.asStateFlow()

    private val _histNow = MutableStateFlow(0L)
    val histNow: StateFlow<Long> = _histNow.asStateFlow()

    private val _marketClosed = MutableStateFlow(false)
    val marketClosed: StateFlow<Boolean> = _marketClosed.asStateFlow()

    /** True after a debug time-travel found nothing left to replay. */
    private val _timeTravelExhausted = MutableStateFlow(false)
    val timeTravelExhausted: StateFlow<Boolean> = _timeTravelExhausted.asStateFlow()

    private val _riskPercent = MutableStateFlow(1.0)
    val riskPercent: StateFlow<Double> = _riskPercent.asStateFlow()

    private val _dataStats = MutableStateFlow(DataStats())
    val dataStats: StateFlow<DataStats> = _dataStats.asStateFlow()

    /** True only if generated placeholder data was ever imported; drives the red banner. */
    private val _fakeActive = MutableStateFlow(false)
    val fakeActive: StateFlow<Boolean> = _fakeActive.asStateFlow()

    /** True when a dataset re-import moved the range; the in-progress season must be reset. */
    private val _datasetChanged = MutableStateFlow(false)
    val datasetChanged: StateFlow<Boolean> = _datasetChanged.asStateFlow()

    private var news: List<NewsEvent> = emptyList()
    private var seasonId: Long = 0L
    private var offsetMs: Long = 0L
    private var ready = false

    /** Serialises catch-up: the minute tick, resume and debug travel can all trigger it. */
    private val catchUpMutex = kotlinx.coroutines.sync.Mutex()

    /** Historical-clock shift; displayed times = stored UTC + this, in the device zone. */
    private val _displayOffsetMs = MutableStateFlow(0L)
    val displayOffsetMs: StateFlow<Long> = _displayOffsetMs.asStateFlow()

    private val seasonFlow = seasons.activeFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val liveOrders: StateFlow<List<TradeOrder>> = seasonFlow
        .flatMapLatest { s -> if (s == null) flowOf(emptyList()) else trading.liveOrders(s.id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val history: StateFlow<List<TradeOrder>> = seasonFlow
        .flatMapLatest { s ->
            if (s == null) flowOf(emptyList())
            else trading.closedSince(s.id, ClockEngine.lastVisibleCandleTs(s.lastProcessedTs))
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            bootstrap()
        }
    }

    private suspend fun bootstrap() {
        // Apply the saved theme before anything heavy, so the UI colours are right early.
        controller.setTheme(themeForId(preferences.themeIdOnce()))
        controller.setLabelSize(PriceLabelSize.fromId(preferences.labelSizeIdOnce()))
        val importedRows = settings.get(SettingsRepository.IMPORT_DONE, "0").toLongOrNull() ?: 0L
        val storedSource = DatasetSource.fromId(settings.get(SettingsRepository.DATA_SOURCE, ""))
        // allowFake is never passed here: production code must not replay generated data.
        // One-time repair: an earlier install that imported a tiny (or no) asset is
        // superseded by a real import (or surfaced as an error) once the bundle is present.
        // `force` rewrites candle_1m and news_event only, preserving trades/season rows.
        val needsImport = importedRows < DatasetImporter.MIN_EXPECTED_ROWS ||
            storedSource == null || storedSource == DatasetSource.MISSING ||
            storedSource == DatasetSource.FAKE || storedSource == DatasetSource.UNVERIFIED
        if (needsImport) {
            val result = DatasetImporter.import(db, assets, force = true) { p -> reportImport(p) }
            if (result.source == DatasetSource.MISSING || result.source == DatasetSource.UNVERIFIED) {
                _startup.value = StartupState(
                    phase = StartupPhase.ERROR,
                    error = missingAssetMessage(result),
                )
                return
            }
            settings.put(SettingsRepository.IMPORT_DONE, result.candleCount.toString())
            settings.put(SettingsRepository.DATA_SOURCE, result.source.name)
            _datasetChanged.value = result.rangeChanged
        }
        _fakeActive.value = settings.get(SettingsRepository.DATA_SOURCE, "") == DatasetSource.FAKE.name
        observeRiskPercent()

        if (_datasetChanged.value) {
            // The dataset range moved; the stored season offset no longer matches. Surface
            // it and make the user reset before any catch-up touches a misaligned clock.
            _startup.value = StartupState(phase = StartupPhase.READY)
            return
        }

        startSeason()
        ready = true
        observeTicker()
        observeOrders()
    }

    private fun missingAssetMessage(result: ImportResult): String {
        result.failureReason?.let { return "Dataset rejected.\n\n$it" }
        val name = result.assetName ?: DatasetImporter.CANDLE_ASSET
        return "Bundled market data is missing or too small.\n\n" +
            "Looked for assets/$name (and ${DatasetImporter.CANDLE_ASSET_PLAIN}); " +
            "found ${result.assetRows} candle rows, need at least ${DatasetImporter.MIN_EXPECTED_ROWS}.\n\n" +
            "The app will not run on generated placeholder data."
    }

    /** Season-dependent startup, shared by first launch and the debug reset. */
    private suspend fun startSeason() {
        val season = seasons.ensureSeason()
        seasonId = season.id
        offsetMs = season.offsetMs
        _displayOffsetMs.value = offsetMs
        controller.setDisplayOffset(offsetMs)
        runCatchUp(initial = true)
        loadInitialWindow()
    }

    /** Debug-only: wipe all trades/season state, then replay a fresh $10,000 season. */
    fun debugResetSeason() {
        viewModelScope.launch {
            seasons.resetActive()
            _datasetChanged.value = false
            startSeason()
            refreshDerived()
            if (!ready) {
                ready = true
                observeTicker()
                observeOrders()
            }
        }
    }

    /**
     * Debug-only: advance the replayed clock by [minutes] and run the normal catch-up.
     * Only the season's clock offset changes; candle and trade data are untouched. The
     * clock is clamped so it can never move past the last bundled candle.
     */
    fun debugTimeTravel(minutes: Long) {
        viewModelScope.launch {
            val season = seasons.active() ?: return@launch
            val updated = seasons.timeTravel(season.id, minutes)
            _timeTravelExhausted.value = updated == null
            if (updated == null) return@launch
            offsetMs = updated.offsetMs
            _displayOffsetMs.value = offsetMs
            controller.setDisplayOffset(offsetMs)
            runCatchUp(initial = false)
            loadInitialWindow()
        }
    }

    private fun observeRiskPercent() {
        viewModelScope.launch {
            settings.flow(SettingsRepository.RISK_PERCENT, "1.0").collect { text ->
                _riskPercent.value = text.toDoubleOrNull()?.coerceIn(0.1, 10.0) ?: 1.0
            }
        }
    }

    private fun reportImport(progress: ImportProgress) {
        _startup.value = _startup.value.copy(
            phase = StartupPhase.IMPORTING,
            importFraction = if (progress.total > 0) progress.fraction
            else if (progress.phase == ImportProgress.Phase.NEWS) 1f else 0.5f,
        )
    }

    private suspend fun runCatchUp(initial: Boolean) {
        catchUpMutex.withLock { runCatchUpLocked(initial) }
    }

    private suspend fun runCatchUpLocked(initial: Boolean) {
        val season = seasons.active() ?: return
        seasonId = season.id
        val now = seasons.histNow(season)
        _histNow.value = now
        val from = season.lastProcessedTs
        val gap = db.candleDao().countBetween(from, now)
        if (gap > LARGE_GAP) {
            _startup.value = _startup.value.copy(phase = StartupPhase.CATCHING_UP, catchUpFrom = from, catchUpTo = now)
        }
        val result = withContext(Dispatchers.Default) { catchUp.run(seasonId, now) }
        if (gap > LARGE_GAP || initial) _startup.value = _startup.value.copy(phase = StartupPhase.READY)
        if (result.processedCandles > 0) refreshDerived()
    }

    private suspend fun loadInitialWindow() {
        news = db.newsDao().all().map { NewsEvent(it.ts, it.title, it.impact) }
        val window = candles.upTo(_histNow.value, CandleRepository.DEFAULT_WINDOW)
        controller.setDisplayOffset(offsetMs)
        controller.replaceData(window, news)
        refreshDerived()
        _quote.value = quoteFrom(window.lastOrNull())
        refreshDataStats()
    }

    /** Re-read the small aggregates the "Data (debug)" panel shows, straight from Room. */
    private suspend fun refreshDataStats() {
        val summary = DatabaseIntrospector.summary(db)
        val season = seasons.active()
        val now = season?.let { seasons.histNow(it) } ?: 0L
        val lastVisible = if (now > 0L) ClockEngine.lastVisibleCandleTs(now) else 0L
        val weeksAhead = if (summary.lastTs > lastVisible) {
            ((summary.lastTs - lastVisible) / MarketTime.WEEK_MS).toInt()
        } else 0
        val meta = DatasetMeta.read(assets)
        _dataStats.value = DataStats(
            rowCount = summary.candleCount,
            firstTs = summary.firstTs,
            lastTs = summary.lastTs,
            closeFirst = summary.closeFirst,
            closeLast = summary.closeLast,
            closeMin = summary.closeMin,
            closeMedian = summary.closeMedian,
            closeMax = summary.closeMax,
            newsCount = summary.newsCount,
            weekdayGapCount = DatabaseIntrospector.weekdayGaps(summary).size,
            offsetMs = season?.offsetMs ?: 0L,
            histNow = now,
            lastVisibleTs = lastVisible,
            weeksAhead = weeksAhead,
            source = DatasetSource.fromId(settings.get(SettingsRepository.DATA_SOURCE, ""))
                ?: DatasetSource.MISSING,
            assetName = DatasetImporter.bundledCandleAsset(assets),
            metaSource = meta?.source,
            metaFetchedAt = meta?.fetchedAt,
            metaRowCount = meta?.rowCount ?: 0L,
            failureReason = _startup.value.error,
        )
    }

    private fun observeTicker() {
        viewModelScope.launch {
            candles.ticker.collect { visibleTs ->
                val now = seasons.active()?.let { seasons.histNow(it) } ?: visibleTs
                _histNow.value = now
                // Fold every newly visible candle into the engine on the minute tick, so a
                // market order fills when the next candle closes instead of only on reopen.
                // runCatchUp is a no-op when there is nothing new to replay.
                runCatchUp(initial = false)
                val latest = candles.latestClosed(now)
                if (latest != null) {
                    controller.appendM1(latest, now + offsetMs)
                }
                refreshDerived()
                _quote.value = quoteFrom(latest)
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeOrders() {
        viewModelScope.launch {
            seasonFlow
                .flatMapLatest { s -> if (s == null) flowOf(emptyList()) else trading.allOrders(s.id) }
                .collect { orders -> applyOverlays(orders) }
        }
    }

    private fun applyOverlays(orders: List<TradeOrder>) {
        val quote = _quote.value
        val bid = quote.bid
        val lines = ArrayList<ChartOrderLine>()
        val markers = ArrayList<ChartMarker>()
        for (o in orders) {
            when (o.status) {
                OrderStatus.OPEN -> {
                    val entry = o.entryPrice ?: continue
                    val handles = buildList {
                        if (o.sl == null) add(OrderLineKind.SL)
                        if (o.tp == null) add(OrderLineKind.TP)
                    }
                    lines.add(
                        ChartOrderLine(
                            id = o.id, kind = OrderLineKind.ENTRY, price = entry, label = "Entry ${o.lots}L",
                            pnlText = pnlTag(o, bid), positive = pnl(o, bid) >= 0, draggable = false,
                            side = o.side, lots = o.lots, entryPrice = entry, handles = handles,
                        ),
                    )
                    o.sl?.let {
                        lines.add(levelLine(o, OrderLineKind.SL, it, entry))
                    }
                    o.tp?.let {
                        lines.add(levelLine(o, OrderLineKind.TP, it, entry))
                    }
                    controller.barIndexForTs(o.openedAt ?: 0L).takeIf { it >= 0 }?.let {
                        markers.add(ChartMarker(it, entry, entry = true, long = o.side == Side.LONG))
                    }
                }
                OrderStatus.PENDING -> {
                    val price = o.entryPrice ?: continue
                    val handles = buildList {
                        if (o.sl == null) add(OrderLineKind.SL)
                        if (o.tp == null) add(OrderLineKind.TP)
                    }
                    lines.add(
                        ChartOrderLine(
                            id = o.id, kind = OrderLineKind.PENDING, price = price, label = pendingLabel(o),
                            draggable = true, side = o.side, lots = o.lots, entryPrice = price, handles = handles,
                        ),
                    )
                    o.sl?.let { lines.add(levelLine(o, OrderLineKind.SL, it, price)) }
                    o.tp?.let { lines.add(levelLine(o, OrderLineKind.TP, it, price)) }
                }
                OrderStatus.CLOSED -> {
                    val exit = o.closePrice ?: continue
                    val at = o.closedAt ?: continue
                    controller.barIndexForTs(at).takeIf { it >= 0 }?.let {
                        markers.add(ChartMarker(it, exit, entry = false, long = o.side == Side.LONG))
                    }
                }
                OrderStatus.CANCELLED -> {}
            }
        }
        controller.setOverlay(ChartOverlayState(lines = lines, markers = markers, bid = bid, spread = quote.spread))
    }

    private fun levelLine(o: TradeOrder, kind: OrderLineKind, price: Double, entry: Double) = ChartOrderLine(
        id = o.id, kind = kind, price = price, draggable = true,
        side = o.side, lots = o.lots, entryPrice = entry,
    )

    /**
     * Persist a level edit from the chart through the same repository the Positions editors
     * use, so both paths stay identical. Returns the repository's verdict for the UI.
     */
    suspend fun applyLevelOutcome(outcome: LevelOutcome): Boolean {
        var ok = true
        catchUpMutex.withLock {
            when (outcome) {
                is LevelOutcome.Set -> {
                    if (outcome.kind == OrderLineKind.PENDING) {
                        trading.movePendingPrice(seasonId, outcome.id, outcome.price)
                    } else {
                        val o = trading.order(outcome.id)
                        if (o != null) {
                            val sl = if (outcome.kind == OrderLineKind.SL) outcome.price else o.sl
                            val tp = if (outcome.kind == OrderLineKind.TP) outcome.price else o.tp
                            trading.editStops(seasonId, outcome.id, sl, tp)
                        } else ok = false
                    }
                }
                is LevelOutcome.Clear -> {
                    val o = trading.order(outcome.id)
                    if (o != null) {
                        val sl = if (outcome.kind == OrderLineKind.SL) null else o.sl
                        val tp = if (outcome.kind == OrderLineKind.TP) null else o.tp
                        trading.editStops(seasonId, outcome.id, sl, tp)
                    } else ok = false
                }
                else -> {}
            }
        }
        refreshDerived()
        return ok
    }

    private fun pnl(o: TradeOrder, bid: Double): Double =
        if (bid <= 0.0) 0.0
        else if (o.side == Side.LONG) (bid - (o.entryPrice ?: bid)) * FillEngine.LOT_OZ * o.lots
        else ((o.entryPrice ?: bid) - bid) * FillEngine.LOT_OZ * o.lots

    private fun pnlTag(o: TradeOrder, bid: Double): String? {
        if (bid <= 0.0) return null
        val p = pnl(o, bid)
        return (if (p >= 0) "+" else "") + "%.2f".format(p)
    }

    private fun pendingLabel(o: TradeOrder): String = when (o.type) {
        OrderType.BUY_LIMIT -> "Buy limit"
        OrderType.BUY_STOP -> "Buy stop"
        OrderType.SELL_LIMIT -> "Sell limit"
        OrderType.SELL_STOP -> "Sell stop"
        OrderType.MARKET -> "Market"
    }

    private fun quoteFrom(last: Candle?): Quote {
        if (last == null) return Quote(0.0, 0.0, 0.0)
        val spread = FillEngine.spreadAt(last.ts, news)
        return Quote(bid = last.c, ask = last.c + spread, spread = spread)
    }

    private suspend fun refreshDerived() {
        val season = seasons.active() ?: return
        val state = loadAccountState()
        val bid = _quote.value.bid
        val floating = state.positions.sumOf { pos ->
            if (bid <= 0.0) 0.0
            else if (pos.side == Side.LONG) (bid - pos.entryPrice) * FillEngine.LOT_OZ * pos.lots
            else (pos.entryPrice - bid) * FillEngine.LOT_OZ * pos.lots
        }
        val equity = state.balance + floating
        val day = dailyStats.forDay(seasonId, com.tradequest.engine.MarketCalendar.dayStart(_histNow.value))
        val margin = state.positions.sumOf { (if (bid > 0.0) bid else it.entryPrice) * it.lots }
        val level = if (margin <= 0.0) Double.MAX_VALUE else equity / margin * 100.0
        _strip.value = AccountStrip(
            balance = state.balance,
            equity = equity,
            floatingPnl = floating,
            dayPnl = equity - (day?.startEquity ?: season.startBalance),
            marginLevel = level,
            openPositions = state.positions.size,
            pendingOrders = state.orders.size,
        )
        val closed = isMarketClosed(_histNow.value)
        _marketClosed.value = closed
        controller.setMarketClosed(closed)
        sampleEquity(season, state, equity, margin)
    }

    /** Append one equity point per closed minute so the challenge can be charted later. */
    private suspend fun sampleEquity(season: Season, state: AccountState, equity: Double, margin: Double) {
        val ts = ClockEngine.lastVisibleCandleTs(_histNow.value)
        if (ts <= 0L || ts <= (snapshots.latest(season.id)?.ts ?: Long.MIN_VALUE)) return
        snapshots.insertAll(
            listOf(EquitySnapshotEntity(season.id, ts, equity, state.balance, margin)),
        )
    }

    private suspend fun loadAccountState() =
        AccountCheckpoint
            .decode(db.settingsDao().get(AccountCheckpoint.KEY), seasons.active()?.startBalance ?: 0.0)
            .toEngine()

    // ---------------------------------------------------------------- actions

    /** Re-run catch-up when the app comes back to the foreground. */
    fun onResume() {
        if (!ready) return
        viewModelScope.launch { runCatchUp(initial = false) }
    }

    fun setTimeframe(tf: Timeframe) = controller.setTimeframe(tf)

    /** Applies the theme immediately and persists the choice for the next launch. */
    fun setTheme(theme: com.tradequest.chart.ChartTheme) {
        controller.setTheme(theme)
        viewModelScope.launch { preferences.setThemeId(theme.id.name) }
    }

    /** Applies the price-label size immediately and persists it for the next launch. */
    fun setLabelSize(size: PriceLabelSize) {
        controller.setLabelSize(size)
        viewModelScope.launch { preferences.setLabelSizeId(size.name) }
    }

    fun setRiskPercent(percent: Double) {
        _riskPercent.value = percent.coerceIn(0.1, 10.0)
        viewModelScope.launch { settings.put(SettingsRepository.RISK_PERCENT, _riskPercent.value.toString()) }
    }

    /** Re-read the Data panel aggregates on demand (the sheet's Refresh button). */
    fun refreshData() {
        viewModelScope.launch { refreshDataStats() }
    }

    fun lotsForRisk(stopDistance: Double): Double =
        RiskCalculator.lotsForRisk(_strip.value.equity, _riskPercent.value, stopDistance)

    fun placeOrder(request: OrderRequest) {
        viewModelScope.launch {
            catchUpMutex.withLock { trading.place(seasonId, request, _histNow.value) }
            refreshDerived()
        }
    }

    fun cancelOrder(orderId: Long) {
        viewModelScope.launch {
            catchUpMutex.withLock { trading.cancel(seasonId, orderId) }
            refreshDerived()
        }
    }

    fun closePosition(positionId: Long, lots: Double? = null) {
        viewModelScope.launch {
            // The live market's "now" is the last visible candle; that is the close time.
            catchUpMutex.withLock {
                trading.closePosition(seasonId, positionId, _quote.value.bid, ClockEngine.lastVisibleCandleTs(_histNow.value), lots)
            }
            refreshDerived()
        }
    }

    fun editStops(orderId: Long, sl: Double?, tp: Double?) {
        viewModelScope.launch {
            catchUpMutex.withLock { trading.editStops(seasonId, orderId, sl, tp) }
            refreshDerived()
        }
    }

    /** Bid/ask for the order sheet, defaulting entry to the side's aggressive price. */
    fun entryPriceFor(type: OrderType): Double = when (type) {
        OrderType.MARKET, OrderType.BUY_LIMIT, OrderType.BUY_STOP -> _quote.value.ask
        OrderType.SELL_LIMIT, OrderType.SELL_STOP -> _quote.value.bid
    }

    companion object {
        private const val LARGE_GAP = 5_000L
    }
}

/** The engine calendar decides when the replayed market is shut for the weekend. */
fun isMarketClosed(ts: Long): Boolean = ts > 0L && MarketCalendar.isClosed(ts)
