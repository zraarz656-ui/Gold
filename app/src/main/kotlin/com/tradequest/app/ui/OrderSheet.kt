package com.tradequest.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tradequest.app.ui.theme.TradeNumberField
import com.tradequest.app.ui.theme.tradeColors
import com.tradequest.data.OrderRequest
import com.tradequest.data.OrderRules
import com.tradequest.data.RiskCalculator
import com.tradequest.engine.FillEngine
import com.tradequest.engine.OrderType
import com.tradequest.engine.Side

private val ORDER_TYPES = listOf(
    OrderType.MARKET, OrderType.BUY_LIMIT, OrderType.BUY_STOP, OrderType.SELL_LIMIT, OrderType.SELL_STOP,
)

/** Order entry sheet: type, lots, SL/TP, trailing distance and a risk calculator. */
@Composable
fun OrderSheet(
    quote: Quote,
    equity: Double,
    riskPercent: Double,
    modifier: Modifier = Modifier,
    initialType: OrderType = OrderType.MARKET,
    initialSide: Side = Side.LONG,
    /**
     * The authoritative (last closed candle) quote used for entry defaults, SL/TP validation
     * and the risk calculator. Defaults to [quote]; the app passes the real quote so the
     * smoothed display price on the buttons never changes whether an order is accepted.
     */
    validationQuote: Quote = quote,
    onDismiss: () -> Unit,
    onPlace: (OrderRequest) -> Unit,
) {
    var type by remember { mutableStateOf(initialType) }
    var marketSide by remember { mutableStateOf(initialSide) }
    var lots by remember { mutableStateOf("0.10") }
    var price by remember { mutableStateOf("") }
    var sl by remember { mutableStateOf("") }
    var tp by remember { mutableStateOf("") }
    var trail by remember { mutableStateOf("") }
    var useRisk by remember { mutableStateOf(false) }
    var riskText by remember { mutableStateOf("%.1f".format(riskPercent)) }
    var error by remember { mutableStateOf<String?>(null) }

    // Pending types carry their own side; a market order needs the explicit toggle.
    val pendingSide = when (type) {
        OrderType.SELL_LIMIT, OrderType.SELL_STOP -> Side.SHORT
        OrderType.MARKET, OrderType.BUY_LIMIT, OrderType.BUY_STOP -> Side.LONG
    }
    val side = if (type == OrderType.MARKET) marketSide else pendingSide
    val entry = price.toDoubleOrNull() ?: when (type) {
        OrderType.MARKET -> if (side == Side.LONG) validationQuote.ask else validationQuote.bid
        OrderType.BUY_LIMIT, OrderType.BUY_STOP -> validationQuote.ask
        else -> validationQuote.bid
    }
    val stopDistance = RiskCalculator.stopDistance(entry, sl.toDoubleOrNull())
    val calculatedLots = if (useRisk && stopDistance != null) {
        RiskCalculator.lotsForRisk(equity, riskText.toDoubleOrNull() ?: riskPercent, stopDistance)
    } else {
        null
    }
    val effectiveLots = calculatedLots ?: (lots.toDoubleOrNull() ?: 0.0)

    fun place(t: OrderType, s: Side) {
        val request = OrderRequest(
            type = t,
            side = s,
            lots = effectiveLots,
            price = price.toDoubleOrNull(),
            sl = sl.toDoubleOrNull(),
            tp = tp.toDoubleOrNull(),
            trailingDist = trail.toDoubleOrNull(),
        )
        val entry = price.toDoubleOrNull() ?: when (t) {
            OrderType.MARKET -> if (s == Side.LONG) validationQuote.ask else validationQuote.bid
            OrderType.BUY_LIMIT, OrderType.BUY_STOP -> validationQuote.ask
            else -> validationQuote.bid
        }
        OrderRules.requestError(request, bid = validationQuote.bid, ask = validationQuote.ask, spread = validationQuote.spread, entry = entry)?.let {
            error = it
            return
        }
        onPlace(request)
        onDismiss()
    }

    val c = tradeColors
    CompositionLocalProvider(LocalContentColor provides c.onSurface) {
        Column(
            modifier
                .fillMaxWidth()
                .background(c.surfaceVariant)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ORDER_TYPES.forEach { t ->
                    Chip(shortLabel(t), selected = type == t) { type = t }
                }
            }

            if (type == OrderType.MARKET) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Buy", selected = marketSide == Side.LONG) { marketSide = Side.LONG }
                    Chip("Sell", selected = marketSide == Side.SHORT) { marketSide = Side.SHORT }
                }
            } else {
                TradeNumberField("Trigger price", price, onChange = { price = it })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TradeNumberField(
                    "Lots",
                    calculatedLots?.let { "%.2f".format(it) } ?: lots,
                    modifier = Modifier.weight(1f),
                    onChange = { lots = it },
                )
                Stepper(onMinus = { lots = stepLots(lots, -0.01) }, onPlus = { lots = stepLots(lots, 0.01) })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TradeNumberField("SL", sl, Modifier.weight(1f)) { sl = it }
                TradeNumberField("TP", tp, Modifier.weight(1f)) { tp = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TradeNumberField("Trailing distance", trail, Modifier.weight(1f)) { trail = it }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Risk calc", selected = useRisk) { useRisk = !useRisk }
                if (useRisk) {
                    TradeNumberField("Risk %", riskText, Modifier.weight(1f)) { riskText = it }
                    Text(
                        stopDistance?.let { "→ ${"%.2f".format(calculatedLots ?: 0.0)} lots @ ${"%.2f".format(it)} stop" }
                            ?: "enter a stop",
                        color = c.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }

            error?.let {
                Text(it, color = c.negative, fontSize = 11.sp)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (type == OrderType.MARKET) {
                    // Both directions are always reachable, regardless of the toggle above.
                    Button(
                        onClick = { place(OrderType.MARKET, Side.LONG) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = c.positive, contentColor = c.onAccent),
                    ) { Text("Buy ${"%.2f".format(quote.ask)}") }
                    Button(
                        onClick = { place(OrderType.MARKET, Side.SHORT) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = c.negative, contentColor = c.onAccent),
                    ) { Text("Sell ${"%.2f".format(quote.bid)}") }
                } else {
                    Button(
                        onClick = { place(type, pendingSide) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (side == Side.LONG) c.positive else c.negative,
                            contentColor = c.onAccent,
                        ),
                    ) { Text("Place ${shortLabel(type)} ${"%.2f".format(entry)}") }
                }
                Button(onClick = { onDismiss() }, modifier = Modifier.weight(1f)) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun Stepper(onMinus: () -> Unit, onPlus: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Button(onClick = onPlus, contentPadding = PaddingValues(0.dp)) { Text("+") }
        Button(onClick = onMinus, contentPadding = PaddingValues(0.dp)) { Text("−") }
    }
}

private fun stepLots(current: String, delta: Double): String {
    val value = (current.toDoubleOrNull() ?: 0.0) + delta
    return "%.2f".format(FillEngine.roundLots(value.coerceAtLeast(0.0)))
}

private fun shortLabel(t: OrderType): String = when (t) {
    OrderType.MARKET -> "Market"
    OrderType.BUY_LIMIT -> "Buy limit"
    OrderType.BUY_STOP -> "Buy stop"
    OrderType.SELL_LIMIT -> "Sell limit"
    OrderType.SELL_STOP -> "Sell stop"
}

/** A quick Buy/Sell row shown on the chart when the sheet is closed. */
@Composable
fun BuySellBar(quote: Quote, onOpen: (OrderType, Side) -> Unit, modifier: Modifier = Modifier) {
    val c = tradeColors
    Row(
        modifier.fillMaxWidth().background(c.surfaceVariant).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { onOpen(OrderType.MARKET, Side.LONG) },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = c.positive, contentColor = c.onAccent),
        ) { Text("Buy ${"%.2f".format(quote.ask)}") }
        Button(
            onClick = { onOpen(OrderType.MARKET, Side.SHORT) },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = c.negative, contentColor = c.onAccent),
        ) { Text("Sell ${"%.2f".format(quote.bid)}") }
    }
}
