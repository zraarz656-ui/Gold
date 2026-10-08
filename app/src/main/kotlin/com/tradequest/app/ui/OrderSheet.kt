package com.tradequest.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tradequest.data.OrderRequest
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
    onDismiss: () -> Unit,
    onPlace: (OrderRequest) -> Unit,
) {
    var type by remember { mutableStateOf(OrderType.MARKET) }
    var lots by remember { mutableStateOf("0.10") }
    var price by remember { mutableStateOf("") }
    var sl by remember { mutableStateOf("") }
    var tp by remember { mutableStateOf("") }
    var trail by remember { mutableStateOf("") }
    var useRisk by remember { mutableStateOf(false) }
    var riskText by remember { mutableStateOf("%.1f".format(riskPercent)) }

    val side = if (type == OrderType.SELL_LIMIT || type == OrderType.SELL_STOP) Side.SHORT else Side.LONG
    val entry = price.toDoubleOrNull() ?: when (type) {
        OrderType.MARKET, OrderType.BUY_LIMIT, OrderType.BUY_STOP -> quote.ask
        else -> quote.bid
    }
    val stopDistance = RiskCalculator.stopDistance(entry, sl.toDoubleOrNull())
    val calculatedLots = if (useRisk && stopDistance != null) {
        RiskCalculator.lotsForRisk(equity, riskText.toDoubleOrNull() ?: riskPercent, stopDistance)
    } else {
        null
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(PanelBg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ORDER_TYPES.forEach { t ->
                Chip(shortLabel(t), selected = type == t) { type = t }
            }
        }

        if (type != OrderType.MARKET) {
            NumberField("Trigger price", price, { price = it })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Lots", calculatedLots?.let { "%.2f".format(it) } ?: lots, { lots = it }, Modifier.weight(1f))
            Stepper(onMinus = { lots = stepLots(lots, -0.01) }, onPlus = { lots = stepLots(lots, 0.01) })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("SL", sl, { sl = it }, Modifier.weight(1f))
            NumberField("TP", tp, { tp = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Trailing distance", trail, { trail = it }, Modifier.weight(1f))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Risk calc", selected = useRisk) { useRisk = !useRisk }
            if (useRisk) {
                NumberField("Risk %", riskText, { riskText = it }, Modifier.weight(1f))
                Text(
                    stopDistance?.let { "→ ${"%.2f".format(calculatedLots ?: 0.0)} lots @ ${"%.2f".format(it)} stop" }
                        ?: "enter a stop",
                    color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 16.dp),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val request = OrderRequest(
                        type = type,
                        lots = (calculatedLots ?: lots.toDoubleOrNull() ?: 0.0),
                        price = price.toDoubleOrNull(),
                        sl = sl.toDoubleOrNull(),
                        tp = tp.toDoubleOrNull(),
                        trailingDist = trail.toDoubleOrNull(),
                    )
                    onPlace(request)
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (side == Side.LONG) Positive else Negative,
                ),
            ) { Text(if (side == Side.LONG) "Buy ${"%.2f".format(quote.ask)}" else "Sell ${"%.2f".format(quote.bid)}") }
            Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun Stepper(onMinus: () -> Unit, onPlus: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Button(onClick = onPlus, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("+") }
        Button(onClick = onMinus, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("−") }
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
fun BuySellBar(quote: Quote, onOpen: (OrderType) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(PanelBg).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { onOpen(OrderType.MARKET) },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = Positive),
        ) { Text("Buy ${"%.2f".format(quote.ask)}") }
        Button(
            onClick = { onOpen(OrderType.MARKET) },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = Negative),
        ) { Text("Sell ${"%.2f".format(quote.bid)}") }
    }
}
