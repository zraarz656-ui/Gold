package com.tradequest.chart

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
import com.tradequest.engine.NewsEvent

/**
 * The Phase 2 chart screen: a candle chart with a timeframe selector, a live toggle and
 * a crosshair readout. Phase 3 layers the trading UI (order sheet, positions, equity
 * strip) on top of this.
 */
@Composable
fun ChartScreen(
    viewModel: ChartViewModel,
    modifier: Modifier = Modifier,
    showDebug: Boolean = false,
) {
    val controller = viewModel.controller
    if (controller == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading data…")
        }
        return
    }

    var crosshair by remember { mutableStateOf<CrosshairInfo?>(null) }
    var newsPopup by remember { mutableStateOf<NewsEvent?>(null) }
    val state = controller.state

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(state.theme.background),
    ) {
        TimeframeBar(controller)
        Box(Modifier.weight(1f)) {
            CandleChart(
                controller = controller,
                crosshair = crosshair,
                onCrosshairChange = { crosshair = it },
                onNewsTap = { newsPopup = it },
            )
            crosshair?.let {
                CrosshairReadout(controller, it, Modifier.align(Alignment.TopStart).padding(8.dp))
            }
            newsPopup?.let {
                NewsPopup(it, Modifier.align(Alignment.TopEnd).padding(8.dp)) { newsPopup = null }
            }
            if (!controller.state.liveEdgeFollowing) {
                JumpToLatestBadge(controller, Modifier.align(Alignment.BottomEnd).padding(8.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChartTheme.all.forEach { t ->
                    Pill(t.name, selected = state.theme.id == t.id) { controller.setTheme(t) }
                }
            }
            Pill(if (viewModel.liveRunning) "Stop" else "Live") { viewModel.toggleLive() }
        }
    }
}

@Composable
private fun TimeframeBar(controller: ChartController) {
    val state = controller.state
    Row(
        Modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TIMEFRAMES.forEach { tf ->
            Pill(tf.label(), selected = state.timeframe == tf) { controller.setTimeframe(tf) }
        }
    }
}

@Composable
private fun CrosshairReadout(controller: ChartController, info: CrosshairInfo, modifier: Modifier = Modifier) {
    val theme = controller.state.theme
    val c = info.candle
    val text = "O ${formatPrice(c.o)}  H ${formatPrice(c.h)}  L ${formatPrice(c.l)}  C ${formatPrice(c.c)}\n" +
        "Vol ${c.v.toLong()}   Px ${formatPrice(info.price)}"
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(theme.grid)
            .padding(8.dp),
        color = theme.axisText,
        fontSize = 11.sp,
    )
}

@Composable
private fun NewsPopup(news: NewsEvent, modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    Text(
        "${news.impact}  ${news.title}",
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xCC000000))
            .clickable { onDismiss() }
            .padding(8.dp),
        color = Color.White,
        fontSize = 12.sp,
    )
}

@Composable
private fun JumpToLatestBadge(controller: ChartController, modifier: Modifier = Modifier) {
    val n = controller.pendingNewCandles
    Pill(if (n > 0) "▼ $n new" else "▼ Latest", selected = true, modifier = modifier) { controller.jumpToLatest() }
}

@Composable
private fun Pill(label: String, selected: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Color(0xFF2A3441) else Color(0x223FFFFFFF))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = if (selected) Color.White else Color(0xFFB0BEC5),
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
    )
}
