package com.tradequest.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradequest.app.BuildConfig
import com.tradequest.app.ui.theme.tradeColors
import com.tradequest.chart.ChartPanel
import com.tradequest.chart.ChartTheme
import com.tradequest.chart.CrosshairInfo
import com.tradequest.chart.PriceLabelSize
import com.tradequest.chart.TIMEFRAMES
import com.tradequest.chart.formatDateTime
import com.tradequest.chart.label
import com.tradequest.data.DatasetImporter
import com.tradequest.data.DatasetSource
import com.tradequest.data.OrderStatus
import com.tradequest.engine.MarketTime
import com.tradequest.engine.OrderType
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** The chart must never be shorter than this share of the screen (20:9 phones). */
private const val MIN_CHART_SCREEN_FRACTION = 0.55f

/** Root screen: chart + equity strip + order entry + positions. */
@Composable
fun TradeQuestScreen(viewModel: TradingViewModel, modifier: Modifier = Modifier) {
    val startup by viewModel.startup.collectAsStateWithLifecycle()
    val quote by viewModel.quote.collectAsStateWithLifecycle()
    val strip by viewModel.strip.collectAsStateWithLifecycle()
    val orders by viewModel.liveOrders.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val offsetMs by viewModel.displayOffsetMs.collectAsStateWithLifecycle()
    val risk by viewModel.riskPercent.collectAsStateWithLifecycle()
    val marketClosed by viewModel.marketClosed.collectAsStateWithLifecycle()
    val closedNotice by viewModel.closedNotice.collectAsStateWithLifecycle()
    val timeTravelExhausted by viewModel.timeTravelExhausted.collectAsStateWithLifecycle()
    val dataStats by viewModel.dataStats.collectAsStateWithLifecycle()
    val fakeActive by viewModel.fakeActive.collectAsStateWithLifecycle()
    val datasetChanged by viewModel.datasetChanged.collectAsStateWithLifecycle()
    val submitError by viewModel.submitError.collectAsStateWithLifecycle()

    if (startup.phase == StartupPhase.ERROR) {
        DataErrorScreen(startup.error ?: "Market data is unavailable.", modifier)
        return
    }
    if (startup.phase != StartupPhase.READY) {
        StartupOverlay(startup, modifier)
        return
    }
    if (datasetChanged) {
        DatasetChangedScreen(onReset = { viewModel.debugResetSeason() }, modifier = modifier)
        return
    }

    var tab by remember { mutableStateOf(0) }
    var sheetFor by remember { mutableStateOf<OrderType?>(null) }
    var sheetSide by remember { mutableStateOf(com.tradequest.engine.Side.LONG) }
    var crosshair by remember { mutableStateOf<CrosshairInfo?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(submitError) {
        submitError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSubmitError()
        }
    }
    val controller = viewModel.controller
    val c = tradeColors
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val minChartHeight = screenHeightDp * MIN_CHART_SCREEN_FRACTION

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(c.surface)) {
            if (fakeActive) FakeDataBanner()
            EquityStrip(strip, quote)
            // Tabs, timeframes and the settings menu all share one row.
            TradeQuestHeader(
                tab = tab,
                liveOrders = orders.count {
                    it.status == OrderStatus.OPEN || it.status == OrderStatus.PENDING || it.status == OrderStatus.QUEUED
                },
                timeframe = controller.state.timeframe,
                onTab = { tab = it },
                onTimeframe = { viewModel.setTimeframe(it) },
                onSettings = { showSettings = true },
            )

            if (tab == 0) {
                Box(Modifier.weight(1f).heightIn(min = minChartHeight)) {
                    ChartPanel(
                        controller = controller,
                        modifier = Modifier.fillMaxSize(),
                        crosshair = crosshair,
                        onCrosshairChange = { crosshair = it },
                        onEntryGroupTap = { tab = 1 },
                        onLevelOutcome = { outcome -> scope.launch { viewModel.applyLevelOutcome(outcome) } },
                        onMessage = { message = it },
                    )
                    message?.let { msg ->
                        LevelMessageBanner(msg) { message = null }
                    }
                }
                if (marketClosed) {
                    ClosedFooter(closedNotice)
                } else {
                    sheetFor?.let { type ->
                        OrderSheet(
                            quote = quote,
                            equity = strip.equity,
                            riskPercent = risk,
                            initialType = type,
                            initialSide = sheetSide,
                            onDismiss = { sheetFor = null },
                            onPlace = { viewModel.placeOrder(it) },
                        )
                    } ?: BuySellBar(quote, onOpen = { type, side -> sheetFor = type; sheetSide = side })
                }
            } else {
                Box(Modifier.weight(1f)) {
                    PositionsScreen(
                        orders = orders,
                        closed = history,
                        displayOffsetMs = offsetMs,
                        bid = quote.bid,
                        onClose = { id, lots -> viewModel.closePosition(id, lots) },
                        onCancel = { viewModel.cancelOrder(it) },
                        onEditStops = { id, sl, tp -> viewModel.editStops(id, sl, tp) },
                        onResetSeason = if (BuildConfig.DEBUG) {
                            { showResetConfirm = true }
                        } else null,
                        onTimeTravel = if (BuildConfig.DEBUG) {
                            { minutes -> viewModel.debugTimeTravel(minutes) }
                        } else null,
                        timeTravelExhausted = timeTravelExhausted,
                    )
                }
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }

    if (showSettings) {
        ChartSettingsSheet(
            theme = controller.state.theme,
            labelSize = controller.state.labelSize,
            showCountdown = controller.state.showCountdown,
            dataStats = dataStats,
            onTheme = { viewModel.setTheme(it) },
            onLabelSize = { viewModel.setLabelSize(it) },
            onShowCountdown = { viewModel.setShowCountdown(it) },
            onRefreshData = { viewModel.refreshData() },
            onDismiss = { showSettings = false },
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset season?") },
            text = { Text("This deletes all trades, stats and equity history, then starts a fresh $10,000 season. Imported market data is kept.") },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    viewModel.debugResetSeason()
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") } },
        )
    }
}

/**
 * The single header row: the Chart/Positions tabs, the timeframe chips and the settings
 * "⋮" button. The timeframe chips scroll horizontally when the row runs out of width, so
 * nothing is ever pushed off-screen.
 */
@Composable
fun TradeQuestHeader(
    tab: Int,
    liveOrders: Int,
    timeframe: com.tradequest.engine.Timeframe,
    onTab: (Int) -> Unit,
    onTimeframe: (com.tradequest.engine.Timeframe) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chip("Chart", tab == 0) { onTab(0) }
        Chip("Positions ($liveOrders)", tab == 1) { onTab(1) }
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TIMEFRAMES.forEach { tf -> Chip(tf.label(), timeframe == tf) { onTimeframe(tf) } }
        }
        SettingsButton(onSettings)
    }
}

/** The "⋮" affordance that opens the chart settings sheet. */
@Composable
private fun SettingsButton(onClick: () -> Unit) {
    val c = tradeColors
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text("⋮", color = c.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}
/**
 * The chart settings bottom sheet: theme, price-label size and a placeholder for future
 * options. Every choice is persisted through the view model (DataStore).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChartSettingsSheet(
    theme: ChartTheme,
    labelSize: PriceLabelSize,
    showCountdown: Boolean,
    dataStats: DataStats,
    onTheme: (ChartTheme) -> Unit,
    onLabelSize: (PriceLabelSize) -> Unit,
    onShowCountdown: (Boolean) -> Unit,
    onRefreshData: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = tradeColors
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    fun close() = scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Chart settings", color = c.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)

            Text("Theme", color = c.onSurfaceVariant, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChartTheme.all.forEach { t ->
                    Chip(t.name, t.id == theme.id) { onTheme(t); close() }
                }
            }

            Text("Label size", color = c.onSurfaceVariant, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PriceLabelSize.entries.forEach { s ->
                    Chip(s.label, s == labelSize) { onLabelSize(s); close() }
                }
            }

            Text("Candle-close countdown", color = c.onSurfaceVariant, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("On", showCountdown) { onShowCountdown(true) }
                Chip("Off", !showCountdown) { onShowCountdown(false) }
            }

            Text("Data (debug)", color = c.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            DataPanel(dataStats)
            OutlinedButton(onClick = onRefreshData, modifier = Modifier.fillMaxWidth()) {
                Text("Refresh")
            }
        }
    }
}

/** Read-only dataset facts for the debug Data panel. */
@Composable
private fun DataPanel(stats: DataStats) {
    val zone = ZoneId.systemDefault()
    fun utc(ts: Long) = if (ts <= 0L) "—" else formatDateTime(ts, ZoneOffset.UTC)
    fun shown(ts: Long) = if (ts <= 0L) "—" else formatDateTime(ts + stats.offsetMs, zone)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        DataRow("Rows (1m)", "${stats.rowCount}")
        DataRow("First candle", "${utc(stats.firstTs)} UTC")
        DataRow("", "shows ${shown(stats.firstTs)}")
        DataRow("Last candle", "${utc(stats.lastTs)} UTC")
        DataRow("", "shows ${shown(stats.lastTs)}")
        DataRow("Close first", "%.2f".format(stats.closeFirst))
        DataRow("Close last", "%.2f".format(stats.closeLast))
        DataRow("Close min / med / max", "%.2f / %.2f / %.2f".format(
            stats.closeMin, stats.closeMedian, stats.closeMax))
        DataRow("Normal gaps (daily/weekend)", "${stats.normalGapCount}")
        DataRow("Unexpected gaps", "${stats.unexpectedGaps.size}")
        stats.unexpectedGaps.forEach { g ->
            val utcFrom = formatDateTime(g.fromTs, ZoneOffset.UTC)
            val utcTo = formatDateTime(g.toTs, ZoneOffset.UTC)
            DataRow("", "• ${utcFrom} → ${utcTo} UTC")
            DataRow("", "   shows ${formatDateTime(g.fromTs + stats.offsetMs, zone)} →" +
                " ${formatDateTime(g.toTs + stats.offsetMs, zone)}")
            DataRow("", "   length ${g.minutes} min (${g.length})")
        }
        DataRow("News events", "${stats.newsCount}")
        DataRow("Offset", "${stats.offsetMs / MarketTime.WEEK_MS} weeks (${stats.offsetMs} ms)")
        DataRow("histNow", "${utc(stats.histNow)} UTC")
        DataRow("", "shows ${shown(stats.histNow)}")
        DataRow("Last visible candle", "${utc(stats.lastVisibleTs)} UTC")
        DataRow("Weeks ahead of histNow", "${stats.weeksAhead}")
        DataRow("Data source", stats.source.label)
        DataRow("Asset", stats.assetName ?: "—")
        DataRow("Meta source", stats.metaSource ?: "—")
        DataRow("Fetched at", stats.metaFetchedAt ?: "—")
        DataRow("Meta row count", if (stats.metaRowCount > 0L) "${stats.metaRowCount}" else "—")
        stats.failureReason?.let { DataRow("Rejected", it) }
        if (stats.source == DatasetSource.FAKE) {
            DataRow(
                "",
                "PLACEHOLDER — bundled asset missing or < ${DatasetImporter.MIN_EXPECTED_ROWS} rows",
            )
        }
        if (stats.source == DatasetSource.UNVERIFIED) {
            DataRow("", "UNVERIFIED — bundled asset failed checksum/structure checks")
        }
    }
}

@Composable
private fun DataRow(label: String, value: String) {
    val c = tradeColors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = c.onSurfaceVariant, fontSize = 11.sp)
        Text(value, color = c.onSurface, fontSize = 11.sp)
    }
}

/** Loud, unmissable strip while generated placeholder data is active. */
@Composable
internal fun FakeDataBanner(modifier: Modifier = Modifier) {
    val c = tradeColors
    Row(
        modifier.fillMaxWidth().background(c.negative).padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            "FAKE DATA — generated placeholder series, not real history",
            color = c.onAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp,
        )
    }
}

/** Blocking prompt shown after a data re-import moved the dataset range. */
@Composable
internal fun DatasetChangedScreen(onReset: () -> Unit, modifier: Modifier = Modifier) {
    val c = tradeColors
    Box(modifier.fillMaxSize().background(c.surface), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Dataset changed — reset season", color = c.warning, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "The bundled market data now covers a different time range, so the " +
                    "season's clock no longer lines up. Start a fresh season to continue.",
                color = c.onSurface, fontSize = 13.sp,
            )
            Text(
                "Your open trades, stats and equity history are kept until you reset.",
                color = c.onSurfaceVariant, fontSize = 11.sp,
            )
            Button(onClick = onReset) { Text("Reset season") }
        }
    }
}

/** Fatal data problem shown instead of the app when the bundled asset is unusable. */
@Composable
private fun DataErrorScreen(message: String, modifier: Modifier = Modifier) {
    val c = tradeColors
    Box(modifier.fillMaxSize().background(c.surface), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Market data error", color = c.negative, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(message, color = c.onSurface, fontSize = 13.sp)
        }
    }
}

@Composable
private fun StartupOverlay(state: StartupState, modifier: Modifier = Modifier) {
    val c = tradeColors
    Box(modifier.fillMaxSize().background(c.surface), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (state.phase) {
                StartupPhase.IMPORTING -> {
                    Text("Importing market data…", color = c.onSurface, fontSize = 14.sp)
                    LinearProgressIndicator(
                        progress = { state.importFraction },
                        modifier = Modifier.fillMaxWidth(0.7f),
                    )
                }
                StartupPhase.CATCHING_UP -> {
                    CircularProgressIndicator()
                    Text("Catching up…", color = c.onSurface, fontSize = 14.sp)
                    Text(
                        "Replaying candles up to the current market time",
                        color = c.onSurfaceVariant, fontSize = 11.sp,
                    )
                }
                StartupPhase.READY, StartupPhase.ERROR -> {}
            }
        }
    }
}

/** Replaces the order controls while the market is shut for the weekend. */
@Composable
private fun ClosedFooter(notice: ClosedNotice?) {
    val c = tradeColors
    // The "Opens in" figure is refreshed on a lightweight timer, so it stays correct even
    // though no candle closes (and so no ticker fires) while the market is shut.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(notice?.opensAtDisplayMs) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000L)
        }
    }
    val text = if (notice == null) {
        "Market closed. Market orders are queued and fill when the market opens."
    } else {
        val remaining = (notice.opensAtDisplayMs - now).coerceAtLeast(0L)
        val hours = remaining / 3_600_000L
        val minutes = (remaining % 3_600_000L) / 60_000L
        val open = Instant.ofEpochMilli(notice.opensAtDisplayMs).atZone(ZoneId.systemDefault())
        val weekday = open.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
        val hm = String.format(java.util.Locale.US, "%02d:%02d", open.hour, open.minute)
        "Market closed. Opens in ${hours}h ${minutes}m ($weekday $hm local). " +
            "Market orders are queued and fill when the market opens."
    }
    Text(
        text,
        modifier = Modifier.fillMaxWidth().background(c.surfaceVariant).padding(12.dp),
        color = c.onSurfaceVariant,
        fontSize = 12.sp,
    )
}

/** Transient "SL must be below the entry" style feedback for a rejected level drag. */
@Composable
private fun LevelMessageBanner(message: String, onDismiss: () -> Unit) {
    val c = tradeColors
    LaunchedEffect(message) {
        kotlinx.coroutines.delay(2600)
        onDismiss()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Text(
            message,
            modifier = Modifier
                .padding(bottom = 96.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(c.surfaceVariant)
                .clickable { onDismiss() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            color = c.warning,
            fontSize = 12.sp,
        )
    }
}
