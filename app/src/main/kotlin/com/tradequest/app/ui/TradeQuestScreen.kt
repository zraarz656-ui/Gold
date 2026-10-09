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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
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
import com.tradequest.chart.label
import com.tradequest.data.OrderStatus
import com.tradequest.engine.OrderType
import kotlinx.coroutines.launch

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
    val timeTravelExhausted by viewModel.timeTravelExhausted.collectAsStateWithLifecycle()

    if (startup.phase != StartupPhase.READY) {
        StartupOverlay(startup, modifier)
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
    val controller = viewModel.controller
    val c = tradeColors
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val minChartHeight = screenHeightDp * MIN_CHART_SCREEN_FRACTION

    Column(modifier.fillMaxSize().background(c.surface)) {
        EquityStrip(strip, quote)
        // Tabs, timeframes and the settings menu all share one row.
        TradeQuestHeader(
            tab = tab,
            liveOrders = orders.count { it.status == OrderStatus.OPEN || it.status == OrderStatus.PENDING },
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
                if (marketClosed) {
                    MarketClosedBanner(Modifier.align(Alignment.Center).padding(8.dp))
                }
            }
            if (marketClosed) {
                ClosedFooter()
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

    if (showSettings) {
        ChartSettingsSheet(
            theme = controller.state.theme,
            labelSize = controller.state.labelSize,
            onTheme = { viewModel.setTheme(it) },
            onLabelSize = { viewModel.setLabelSize(it) },
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
    onTheme: (ChartTheme) -> Unit,
    onLabelSize: (PriceLabelSize) -> Unit,
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

            Text("More chart options coming soon", color = c.onSurfaceVariant, fontSize = 11.sp)
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
                StartupPhase.READY -> {}
            }
        }
    }
}

/** Small banner reused by the chart when the market is shut. */
@Composable
fun MarketClosedBanner(modifier: Modifier = Modifier) {
    val c = tradeColors
    Text(
        "Market closed",
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(c.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable { },
        color = c.warning,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
    )
}

/** Replaces the order controls while the market is shut for the weekend. */
@Composable
private fun ClosedFooter() {
    val c = tradeColors
    Text(
        "Market closed — orders resume at the Sunday rollover",
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
