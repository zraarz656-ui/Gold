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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.tradequest.data.OrderStatus
import com.tradequest.data.TradeOrder
import com.tradequest.engine.FillEngine
import com.tradequest.engine.Side

/** Open positions and pending orders with edit / close / partial-close actions. */
@Composable
fun PositionsScreen(
    orders: List<TradeOrder>,
    bid: Double,
    modifier: Modifier = Modifier,
    onClose: (Long, Double?) -> Unit,
    onCancel: (Long) -> Unit,
    onEditStops: (Long, Double?, Double?) -> Unit,
) {
    val open = orders.filter { it.status == OrderStatus.OPEN }
    val pending = orders.filter { it.status == OrderStatus.PENDING }

    LazyColumn(
        modifier.fillMaxSize().background(Color(0xFF12161C)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SectionHeader("Open positions (${open.size})") }
        if (open.isEmpty()) item { EmptyRow("No open positions") }
        items(open, key = { "o-${it.id}" }) { o ->
            PositionCard(o, bid, onClose, onEditStops)
        }
        item { SectionHeader("Pending orders (${pending.size})") }
        if (pending.isEmpty()) item { EmptyRow("No pending orders") }
        items(pending, key = { "p-${it.id}" }) { o ->
            PendingCard(o, onCancel)
        }
    }
}

@Composable
private fun PositionCard(
    order: TradeOrder,
    bid: Double,
    onClose: (Long, Double?) -> Unit,
    onEditStops: (Long, Double?, Double?) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val entry = order.entryPrice ?: 0.0
    val pnl = if (order.side == Side.LONG) (bid - entry) * FillEngine.LOT_OZ * order.lots
    else (entry - bid) * FillEngine.LOT_OZ * order.lots

    Column(Modifier.fillMaxWidth().background(PanelBg).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${if (order.side == Side.LONG) "LONG" else "SHORT"} ${"%.2f".format(order.lots)} @ ${"%.2f".format(entry)}",
                color = if (order.side == Side.LONG) Positive else Negative,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
            Text(signedMoney(pnl), color = if (pnl >= 0) Positive else Negative, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Text(
            "SL ${order.sl?.let { "%.2f".format(it) } ?: "—"}   TP ${order.tp?.let { "%.2f".format(it) } ?: "—"}" +
                (order.trailingDist?.let { "   trail ${"%.2f".format(it)}" } ?: ""),
            color = Muted, fontSize = 11.sp,
        )
        if (editing) {
            var sl by remember { mutableStateOf(order.sl?.toString() ?: "") }
            var tp by remember { mutableStateOf(order.tp?.toString() ?: "") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextFieldCompact("SL", sl, Modifier.weight(1f)) { sl = it }
                OutlinedTextFieldCompact("TP", tp, Modifier.weight(1f)) { tp = it }
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
    Column(Modifier.fillMaxWidth().background(PanelBg).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${order.type.name.replace('_', ' ')} ${"%.2f".format(order.lots)} @ ${order.entryPrice?.let { "%.2f".format(it) } ?: "mkt"}",
                color = Color(0xFFFFB300), fontWeight = FontWeight.Bold, fontSize = 13.sp,
            )
            Text("pending", color = Muted, fontSize = 11.sp)
        }
        Text(
            "SL ${order.sl?.let { "%.2f".format(it) } ?: "—"}   TP ${order.tp?.let { "%.2f".format(it) } ?: "—"}",
            color = Muted, fontSize = 11.sp,
        )
        OutlinedButton(onClick = { onCancel(order.id) }) { Text("Cancel") }
    }
}

@Composable
private fun OutlinedTextFieldCompact(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        modifier = modifier,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
        ),
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun EmptyRow(text: String) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Muted, fontSize = 12.sp)
    }
}
