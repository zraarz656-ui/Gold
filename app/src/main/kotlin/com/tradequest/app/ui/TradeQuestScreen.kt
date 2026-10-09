package com.tradequest.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    val scope = rememberCoroutineScope()
    val controller = viewModel.controller
    val c = tradeColors

    Column(modifier.fillMaxSize().background(c.surface)) {
        EquityStrip(strip, quote)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Chart", tab == 0) { tab = 0 }
            val live = orders.count { it.status == OrderStatus.OPEN || it.status == OrderStatus.PENDING }
            Chip("Positions ($live)", tab == 1) { tab = 1 }
            val timeframe = controller.state.timeframe
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                TIMEFRAMES.forEach { tf -> Chip(tf.label(), timeframe == tf) { viewModel.setTimeframe(tf) } }
            }
        }
        ThemePicker(
            current = controller.state.theme,
            onPick = { viewModel.setTheme(it) },
        )
        LabelSizePicker(
            current = controller.state.labelSize,
            onPick = { viewModel.setLabelSize(it) },
        )

        if (tab == 0) {
            Box(Modifier.weight(1f)) {
                ChartPanel(
                    controller = controller,
                    modifier = Modifier.fillMaxSize(),
                    crosshair = crosshair,
                    onCrosshairChange = { crosshair = it },
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

/** A compact theme switcher; picking a theme applies it and persists it via the VM. */
@Composable
private fun ThemePicker(current: ChartTheme, onPick: (ChartTheme) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ChartTheme.all.forEach { t ->
            Chip(t.name, t.id == current.id) { onPick(t) }
        }
    }
}

/** A compact price-label-size switcher; applies and persists via the VM. */
@Composable
private fun LabelSizePicker(current: PriceLabelSize, onPick: (PriceLabelSize) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Labels",
            color = tradeColors.onSurfaceVariant,
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 2.dp),
        )
        PriceLabelSize.entries.forEach { s ->
            Chip(s.label, s == current) { onPick(s) }
        }
    }
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
