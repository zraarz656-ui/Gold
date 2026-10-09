package com.tradequest.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tradequest.app.ui.theme.tradeColors

fun money(v: Double): String {
    val sign = if (v < 0) "-" else ""
    return "$sign$" + "%.2f".format(kotlin.math.abs(v))
}

fun signedMoney(v: Double): String = (if (v >= 0) "+" else "-") + "$" + "%.2f".format(kotlin.math.abs(v))

@Composable
fun EquityStrip(strip: AccountStrip, quote: Quote, modifier: Modifier = Modifier) {
    val c = tradeColors
    Row(
        modifier
            .fillMaxWidth()
            .background(c.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("Equity", color = c.onSurfaceVariant, fontSize = 10.sp)
            Text(money(strip.equity), color = c.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("Bal ${money(strip.balance)}", color = c.onSurfaceVariant, fontSize = 10.sp)
        }
        Column {
            Text("Day P&L", color = c.onSurfaceVariant, fontSize = 10.sp)
            Text(signedMoney(strip.dayPnl), color = if (strip.dayPnl >= 0) c.positive else c.negative, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("Float ${signedMoney(strip.floatingPnl)}", color = if (strip.floatingPnl >= 0) c.positive else c.negative, fontSize = 10.sp)
        }
        Column {
            Text("Margin", color = c.onSurfaceVariant, fontSize = 10.sp)
            val level = if (strip.marginLevel == Double.MAX_VALUE) "—" else "%.0f%%".format(strip.marginLevel)
            Text(level, color = c.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("${strip.openPositions} pos · ${strip.pendingOrders} ord", color = c.onSurfaceVariant, fontSize = 10.sp)
        }
        Column {
            Text("XAUUSD", color = c.onSurfaceVariant, fontSize = 10.sp)
            Text("%.2f".format(quote.bid), color = c.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("spr %.2f".format(quote.spread), color = c.onSurfaceVariant, fontSize = 10.sp)
        }
    }
}

@Composable
fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = tradeColors
    Text(
        label,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) c.accent else c.surfaceVariant)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = if (selected) c.onAccent else c.onSurface,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
    )
}
