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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val PanelBg = Color(0xFF1A1F27)
internal val Positive = Color(0xFF26A69A)
internal val Negative = Color(0xFFEF5350)
internal val Muted = Color(0xFF9AA4B2)

fun money(v: Double): String {
    val sign = if (v < 0) "-" else ""
    return "$sign$" + "%.2f".format(kotlin.math.abs(v))
}

fun signedMoney(v: Double): String = (if (v >= 0) "+" else "-") + "$" + "%.2f".format(kotlin.math.abs(v))

@Composable
fun EquityStrip(strip: AccountStrip, quote: Quote, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(PanelBg)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("Equity", color = Muted, fontSize = 10.sp)
            Text(money(strip.equity), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("Bal ${money(strip.balance)}", color = Muted, fontSize = 10.sp)
        }
        Column {
            Text("Day P&L", color = Muted, fontSize = 10.sp)
            Text(signedMoney(strip.dayPnl), color = if (strip.dayPnl >= 0) Positive else Negative, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("Float ${signedMoney(strip.floatingPnl)}", color = if (strip.floatingPnl >= 0) Positive else Negative, fontSize = 10.sp)
        }
        Column {
            Text("Margin", color = Muted, fontSize = 10.sp)
            val level = if (strip.marginLevel == Double.MAX_VALUE) "—" else "%.0f%%".format(strip.marginLevel)
            Text(level, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("${strip.openPositions} pos · ${strip.pendingOrders} ord", color = Muted, fontSize = 10.sp)
        }
        Column {
            Text("XAUUSD", color = Muted, fontSize = 10.sp)
            Text("%.2f".format(quote.bid), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("spr %.2f".format(quote.spread), color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Color(0xFF2A3441) else Color(0x223FFFFFFF))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = if (selected) Color.White else Muted,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
    )
}
