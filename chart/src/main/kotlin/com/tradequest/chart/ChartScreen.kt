package com.tradequest.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tradequest.engine.NewsEvent
import com.tradequest.engine.Timeframe

/**
 * The Phase 2 chart screen: a candle chart with a timeframe selector, a crosshair readout,
 * a jump-to-latest badge and the developer bar. Phase 3 reuses its pieces through
 * [ChartToolbar], [ChartPanel] and [ChartDevBar].
 */
@Composable
fun ChartScreen(
    viewModel: ChartViewModel,
    modifier: Modifier = Modifier,
    showFps: Boolean = true,
    showGestureDebug: Boolean = true,
) {
    val controller = viewModel.controller
    if (controller == null) {
        Box(
            modifier.fillMaxSize().background(ChartTheme.DARK.background),
            contentAlignment = Alignment.Center,
        ) {
            Text("Generating 90k candles…", color = ChartTheme.DARK.axisText)
        }
        return
    }
    val state = controller.state
    var crosshair by remember { mutableStateOf<CrosshairInfo?>(null) }
    Box(modifier.fillMaxSize().background(state.theme.background), contentAlignment = Alignment.TopStart) {
        Column(Modifier.fillMaxSize()) {
            ChartToolbar(controller)
            ChartPanel(
                controller = controller,
                crosshair = crosshair,
                onCrosshairChange = { crosshair = it },
                modifier = Modifier.fillMaxWidth().weight(1f),
                showFps = showFps,
                showGestureDebug = showGestureDebug,
                debug = viewModel.gestureDebug,
                liveRunning = viewModel.liveRunning,
            )
            ChartDevBar(viewModel)
        }
    }
}

/** Timeframe pills. Phase 2 drew these above the chart; Phase 3 keeps them in its header row. */
@Composable
fun ChartToolbar(
    controller: ChartController,
    modifier: Modifier = Modifier,
    selected: (Timeframe) -> Boolean = { it == controller.state.timeframe },
    onPick: (Timeframe) -> Unit = { controller.setTimeframe(it) },
) {
    val state = controller.state
    Row(
        modifier.fillMaxWidth().background(state.theme.background).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TIMEFRAMES.forEach { tf ->
            Pill(tf.label(), state, selected(tf)) { onPick(tf) }
        }
    }
}

/**
 * The chart itself plus everything that floats over it: crosshair readout, jump-to-latest
 * badge, gesture/FPS overlays and the news dialog. This is the piece Phase 3 embeds.
 */
@Composable
fun ChartPanel(
    controller: ChartController,
    modifier: Modifier = Modifier,
    crosshair: CrosshairInfo? = null,
    onCrosshairChange: (CrosshairInfo?) -> Unit = {},
    onNewsTap: (NewsEvent) -> Unit = {},
    onLevelOutcome: (LevelOutcome) -> Unit = {},
    onMessage: (String) -> Unit = {},
    debug: GestureDebug? = null,
    liveRunning: Boolean = false,
    showFps: Boolean = false,
    showGestureDebug: Boolean = false,
) {
    val state = controller.state
    var debugExpanded by remember { mutableStateOf(true) }
    var newsPopup by remember { mutableStateOf<NewsEvent?>(null) }
    var confirm by remember { mutableStateOf<LevelConfirm?>(null) }

    Box(modifier, contentAlignment = Alignment.TopStart) {
        CandleChart(
            controller = controller,
            modifier = Modifier.fillMaxSize(),
            displayOffsetMs = state.displayOffsetMs,
            debug = debug,
            crosshair = crosshair,
            onCrosshairChange = onCrosshairChange,
            onNewsTap = { newsPopup = it; onNewsTap(it) },
            onLevelOutcome = { outcome ->
                when (outcome) {
                    // A freshly drawn "+SL"/"+TP" waits for confirmation (the preview stays);
                    // moving an existing line commits on release — the drag itself was the confirm.
                    is LevelOutcome.Set ->
                        if (outcome.isNew) {
                            confirm = LevelConfirm(outcome) { ok ->
                                confirm = null
                                controller.cancelLevelDrag()
                                if (ok) onLevelOutcome(outcome)
                            }
                        } else {
                            controller.cancelLevelDrag()
                            onLevelOutcome(outcome)
                        }
                    is LevelOutcome.Rejected -> { controller.cancelLevelDrag(); onMessage(outcome.message) }
                    else -> { controller.cancelLevelDrag(); onLevelOutcome(outcome) }
                }
            },
        )
        confirm?.let { c ->
            LevelConfirmChip(
                c = c,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            )
        }
        val ch = crosshair
        if (ch != null) {
            CrosshairReadout(controller, ch, Modifier.align(Alignment.TopEnd).padding(top = 62.dp))
        }
        ChartZoomButtons(controller, Modifier.align(Alignment.TopStart).padding(6.dp))
        if (!state.liveEdgeFollowing) {
            JumpToLatestBadge(controller, Modifier.align(Alignment.BottomEnd))
        }
        if (showGestureDebug && debug != null) {
            GestureDebugOverlay(
                controller = controller,
                debug = debug,
                liveRunning = liveRunning,
                expanded = debugExpanded,
                onToggle = { debugExpanded = !debugExpanded },
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
            )
        }
        if (showFps) FpsOverlay(Modifier.align(Alignment.BottomEnd))
    }
    newsPopup?.let { ev -> NewsDialog(ev, state.displayOffsetMs) { newsPopup = null } }
}

/**
 * Small semi-transparent zoom/auto-fit buttons floating over the plot's top-left corner,
 * so they never overlap the time axis (which sits along the bottom).
 */
@Composable
fun ChartZoomButtons(controller: ChartController, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        GhostButton("+") { controller.zoomByFactor(1.25f) }
        GhostButton("–") { controller.zoomByFactor(0.8f) }
        GhostButton("⤢") { controller.autoFitPrice() }
    }
}

@Composable
private fun GhostButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .background(Color(0x661A1F27), RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label, color = Color(0xCCFFFFFF), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** The Phase 2 developer bar: live toggle, draw mode, auto-fit and theme pills. */
@Composable
fun ChartDevBar(viewModel: ChartViewModel, modifier: Modifier = Modifier) {
    val controller = viewModel.controller ?: return
    val state = controller.state
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Pill(if (viewModel.liveRunning) "Stop live" else "Live +1/s", state, viewModel.liveRunning) { viewModel.toggleLive() }
        Pill(if (state.drawMode) "Draw: on" else "Draw: off", state, state.drawMode) {
            controller.setDrawMode(!state.drawMode)
        }
        Pill("Auto-fit", state) { controller.resetPriceScale() }
        ChartTheme.all.forEach { t ->
            Pill(t.name, state, state.theme.id == t.id) { controller.setTheme(t) }
        }
    }
}

@Composable
private fun CrosshairReadout(controller: ChartController, crosshair: CrosshairInfo, modifier: Modifier = Modifier) {
    val theme = controller.state.theme
    val c = crosshair.candle
    val pct = GestureMath.changePercent(c)
    val offset = controller.state.displayOffsetMs
    Column(
        modifier
            .padding(6.dp)
            .background(theme.background.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
            .padding(6.dp),
    ) {
        Text(formatDateTime(c.ts + offset), color = theme.axisText, fontSize = 10.sp)
        Text(
            "O ${formatPrice(c.o)}  H ${formatPrice(c.h)}  L ${formatPrice(c.l)}  C ${formatPrice(c.c)}",
            color = theme.axisText,
            fontSize = 10.sp,
        )
        Text(
            "%+.2f%%".format(pct),
            color = if (pct < 0.0) theme.down else theme.up,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
        Text("${formatPrice(crosshair.price)} @ cursor", color = theme.crosshair, fontSize = 10.sp)
    }
}

@Composable
private fun JumpToLatestBadge(controller: ChartController, modifier: Modifier = Modifier) {
    val theme = controller.state.theme
    val pending = controller.pendingNewCandles
    Box(
        modifier
            .padding(10.dp)
            .background(theme.currentPrice, RoundedCornerShape(14.dp))
            .clickable { controller.jumpToLatest() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row {
            Text("Jump to latest", color = Color.White, fontSize = 12.sp)
            if (pending > 0) {
                Box(
                    Modifier
                        .padding(start = 6.dp)
                        .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text("$pending new", color = Color.White, fontSize = 11.sp)
                }
            }
        }
    }
}

/** Tile used by the timeframe bar, the dev bar and the Phase 3 header. */
@Composable
fun Pill(label: String, state: ChartState, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) state.theme.axisText else state.theme.background, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            color = if (selected) state.theme.background else state.theme.axisText,
            fontSize = 12.sp,
        )
    }
}

/** The pending confirm chip shown after a level drag / new handle release. */
private data class LevelConfirm(val outcome: LevelOutcome.Set, val onResult: (Boolean) -> Unit)

/**
 * "Set SL at 2378.50 — OK / Cancel". While it is up the dragged line stays at its preview
 * price; OK commits through the repository, Cancel (or timeout) snaps the line back.
 */
@Composable
private fun LevelConfirmChip(c: LevelConfirm, modifier: Modifier = Modifier) {
    val theme = ChartTheme.DARK
    val kind = when (c.outcome.kind) {
        OrderLineKind.SL -> "SL"
        OrderLineKind.TP -> "TP"
        else -> "Level"
    }
    Row(
        modifier
            .padding(6.dp)
            .background(Color(0xF21A1F27), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Set $kind at ${formatPrice(c.outcome.price)}",
            color = Color.White,
            fontSize = 13.sp,
        )
        TextButton(onClick = { c.onResult(true) }) { Text("OK", color = Color(0xFF26A69A), fontSize = 13.sp) }
        TextButton(onClick = { c.onResult(false) }) { Text("Cancel", color = theme.axisText, fontSize = 13.sp) }
    }
}

@Composable
private fun NewsDialog(news: NewsEvent, displayOffsetMs: Long, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(formatShortDateTime(news.ts + displayOffsetMs)) },
        text = { Text("${news.impact}  ${news.title}") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
