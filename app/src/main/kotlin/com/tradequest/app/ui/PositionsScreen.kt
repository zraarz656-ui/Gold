package com.tradequest.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tradequest.app.ui.theme.TradeNumberField
import com.tradequest.app.ui.theme.tradeColors
import com.tradequest.chart.formatDateTime
import com.tradequest.data.OrderStatus
import com.tradequest.data.TradeOrder
import com.tradequest.engine.FillEngine
import com.tradequest.engine.Side
import androidx.compose.material3.LocalContentColor

/** Open positions and pending orders with edit / close / partial-close actions. */
@Composable
fun PositionsScreen(
    orders: List<TradeOrder>,
    bid: Double,
    modifier: Modifier = Modifier,
    closed: List<TradeOrder> = emptyList(),
    displayOffsetMs: Long = 0L,
    onClose: (Long, Double?) -> Unit,
    onCancel: (Long) -> Unit,
    onEditStops: (Long, Double?, Double?) -> Unit,
    onResetSeason: (() -> Unit)? = null,
    onTimeTravel: ((Long) -> Unit)? = null,
    timeTravelExhausted: Boolean = false,
) {
    val c = tradeColors
    val open = orders.filter { it.status == OrderStatus.OPEN }
    val pending = orders.filter { it.status == OrderStatus.PENDING }
    CompositionLocalProvider(LocalContentColor provides c.onSurface) {
        LazyColumn(
            modifier.fillMaxSize().background(c.surface).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionHeader("Open positions (${open.size})") }
            if (open.isEmpty()) item { EmptyRow("No open positions") }
            items(open, key = { "o-${it.id}" }) { o ->
                PositionCard(o, bid, displayOffsetMs, onClose, onEditStops)
            }
            item { SectionHeader("Pending orders (${pending.size})") }
            if (pending.isEmpty()) item { EmptyRow("No pending orders") }
            items(pending, key = { "p-${it.id}" }) { o ->
                PendingCard(o, onCancel)
            }
            item { SectionHeader("Closed trades (${closed.size})") }
            if (closed.isEmpty()) item { EmptyRow("No closed trades") }
            items(closed, key = { "c-${it.id}" }) { o ->
                ClosedCard(o, displayOffsetMs)
            }
            if (onResetSeason != null) {
                item {
                    OutlinedButton(onClick = onResetSeason, modifier = Modifier.fillMaxWidth()) {
                        Text("Reset season (debug)")
                    }
                }
            }
            if (onTimeTravel != null) {
                item { SectionHeader("Debug: time travel") }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        TIME_TRAVEL_MINUTES.forEach { minutes ->
                            OutlinedButton(onClick = { onTimeTravel(minutes) }, modifier = Modifier.weight(1f)) {
                                Text("+${labelFor(minutes)}")
                            }
                        }
                    }
                }
                item {
                    Text(
                        if (timeTravelExhausted) "No candles left to replay" else "Shifts the replayed clock only; data is unchanged",
                        color = c.onSurfaceVariant, fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

private val TIME_TRAVEL_MINUTES = listOf(10L, 60L, 240L, 1440L)

private fun labelFor(minutes: Long): String = when (minutes) {
    1440L -> "1d"
    else -> "${minutes}m"
}

@Composable
private fun PositionCard(
    order: TradeOrder,
    bid: Double,
    displayOffsetMs: Long,
    onClose: (Long, Double?) -> Unit,
    onEditStops: (Long, Double?, Double?) -> Unit,
) {
    val c = tradeColors
    var editing by remember { mutableStateOf(false) }
    val entry = order.entryPrice ?: 0.0
    val pnl = if (order.side == Side.LONG) (bid - entry) * FillEngine.LOT_OZ * order.lots
    else (entry - bid) * FillEngine.LOT_OZ * order.lots

    Column(Modifier.fillMaxWidth().background(c.surfaceVariant).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${if (order.side == Side.LONG) "LONG" else "SHORT"} ${"%.2f".format(order.lots)} @ ${"%.2f".format(entry)}",
                color = if (order.side == Side.LONG) c.positive else c.negative,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
            Text(signedMoney(pnl), color = if (pnl >= 0) c.positive else c.negative, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Text(
            "SL ${order.sl?.let { "%.2f".format(it) } ?: "—"}   TP ${order.tp?.let { "%.2f".format(it) } ?: "—"}" +
                (order.trailingDist?.let { "   trail ${"%.2f".format(it)}" } ?: ""),
            color = c.onSurfaceVariant, fontSize = 11.sp,
        )
        order.openedAt?.let { Text("Opened ${formatDateTime(it + displayOffsetMs)}", color = c.onSurfaceVariant, fontSize = 10.sp) }
        if (editing) {
            var sl by remember { mutableStateOf(order.sl?.toString() ?: "") }
            var tp by remember { mutableStateOf(order.tp?.toString() ?: "") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TradeNumberField("SL", sl, Modifier.weight(1f)) { sl = it }
                TradeNumberField("TP", tp, Modifier.weight(1f)) { tp = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onEditStops(order.id, sl.toDoubleOrNull(), tp.toDoubleOrNull()); editing = false }) { Text("Save") }
                OutlinedButton(onClick = { editing = false }) { Text("Cancel") }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { editing = true }) { Text("Edit SL/TP") }
                OutlinedButton(onClick = { onClose(order.id, null) }) { Text("Close") }
                OutlinedButton(onClick = { onClose(order.id, order.lots / 2.0) }) { Text("Close ½") }
            }
        }
    }
}

@Composable
private fun PendingCard(order: TradeOrder, onCancel: (Long) -> Unit) {
    val c = tradeColors
    Column(Modifier.fillMaxWidth().background(c.surfaceVariant).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${order.type.name.replace('_', ' ')} ${"%.2f".format(order.lots)} @ ${order.entryPrice?.let { "%.2f".format(it) } ?: "mkt"}",
                color = c.warning, fontWeight = FontWeight.Bold, fontSize = 13.sp,
            )
            Text("pending", color = c.onSurfaceVariant, fontSize = 11.sp)
        }
        Text(
            "SL ${order.sl?.let { "%.2f".format(it) } ?: "—"}   TP ${order.tp?.let { "%.2f".format(it) } ?: "—"}",
            color = c.onSurfaceVariant, fontSize = 11.sp,
        )
        OutlinedButton(onClick = { onCancel(order.id) }) { Text("Cancel") }
    }
}

@Composable
private fun ClosedCard(order: TradeOrder, displayOffsetMs: Long) {
    val c = tradeColors
    val pnl = order.pnl ?: 0.0
    Column(Modifier.fillMaxWidth().background(c.surfaceVariant).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${if (order.side == Side.LONG) "LONG" else "SHORT"} ${"%.2f".format(order.lots)} " +
                    "@ ${order.entryPrice?.let { "%.2f".format(it) } ?: "—"} → ${order.closePrice?.let { "%.2f".format(it) } ?: "—"}",
                color = c.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 12.sp,
            )
            Text(signedMoney(pnl), color = if (pnl >= 0) c.positive else c.negative, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        order.closedAt?.let { Text("Closed ${formatDateTime(it + displayOffsetMs)}", color = c.onSurfaceVariant, fontSize = 10.sp) }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, color = tradeColors.onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun EmptyRow(text: String) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = tradeColors.onSurfaceVariant, fontSize = 12.sp)
    }
}
