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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradequest.chart.ChartPanel
import com.tradequest.chart.CrosshairInfo
import com.tradequest.chart.TIMEFRAMES
import com.tradequest.chart.label
import com.tradequest.data.OrderStatus
import com.tradequest.engine.OrderType

/** Root screen: chart + equity strip + order entry + positions. */
@Composable
fun TradeQuestScreen(viewModel: TradingViewModel, modifier: Modifier = Modifier) {
    val startup by viewModel.startup.collectAsStateWithLifecycle()
    val quote by viewModel.quote.collectAsStateWithLifecycle()
    val strip by viewModel.strip.collectAsStateWithLifecycle()
    val orders by viewModel.liveOrders.collectAsStateWithLifecycle()
    val risk by viewModel.riskPercent.collectAsStateWithLifecycle()
    val marketClosed by viewModel.marketClosed.collectAsStateWithLifecycle()

    if (startup.phase != StartupPhase.READY) {
        StartupOverlay(startup, modifier)
        return
    }

    var tab by remember { mutableStateOf(0) }
    var sheetFor by remember { mutableStateOf<OrderType?>(null) }
    var crosshair by remember { mutableStateOf<CrosshairInfo?>(null) }
    val controller = viewModel.controller

    Column(modifier.fillMaxSize().background(Color(0xFF12161C))) {
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

        if (tab == 0) {
            Box(Modifier.weight(1f)) {
                ChartPanel(
                    controller = controller,
                    modifier = Modifier.fillMaxSize(),
                    crosshair = crosshair,
                    onCrosshairChange = { crosshair = it },
                    onLineDrag = { id, kind, price -> viewModel.dragLine(id, kind, price) },
                )
                if (marketClosed) {
                    MarketClosedBanner(Modifier.align(Alignment.Center).padding(8.dp))
                }
                Row(
                    Modifier.align(Alignment.BottomStart).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Chip("+", selected = false, onClick = { controller.zoomByFactor(1.25f) })
                    Chip("-", selected = false, onClick = { controller.zoomByFactor(0.8f) })
                    Chip("Fit", selected = false, onClick = { controller.autoFitPrice() })
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
                        onDismiss = { sheetFor = null },
                        onPlace = { viewModel.placeOrder(it.copy(type = type)) },
                    )
                } ?: BuySellBar(quote, onOpen = { sheetFor = it })
            }
        } else {
            Box(Modifier.weight(1f)) {
                PositionsScreen(
                    orders = orders,
                    bid = quote.bid,
                    onClose = { id, lots -> viewModel.closePosition(id, lots) },
                    onCancel = { viewModel.cancelOrder(it) },
                    onEditStops = { id, sl, tp -> viewModel.editStops(id, sl, tp) },
                )
            }
        }
    }
}

@Composable
private fun StartupOverlay(state: StartupState, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Color(0xFF12161C)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (state.phase) {
                StartupPhase.IMPORTING -> {
                    Text("Importing market data…", color = Color.White, fontSize = 14.sp)
                    LinearProgressIndicator(
                        progress = { state.importFraction },
                        modifier = Modifier.fillMaxWidth(0.7f),
                    )
                }
                StartupPhase.CATCHING_UP -> {
                    CircularProgressIndicator()
                    Text("Catching up…", color = Color.White, fontSize = 14.sp)
                    Text(
                        "Replaying candles up to the current market time",
                        color = Muted, fontSize = 11.sp,
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
    Text(
        "Market closed",
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xCC1A1F27))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable { },
        color = Color(0xFFFFB300),
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
    )
}

/** Replaces the order controls while the market is shut for the weekend. */
@Composable
private fun ClosedFooter() {
    Text(
        "Market closed — orders resume at the Sunday rollover",
        modifier = Modifier.fillMaxWidth().background(PanelBg).padding(12.dp),
        color = Muted,
        fontSize = 12.sp,
    )
}
