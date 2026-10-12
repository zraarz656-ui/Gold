package com.tradequest.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Developer overlay showing the live gesture counters; tap "▤" to collapse it.
 * Purely a debugging aid carried over from Phase 2.
 */
@Composable
fun GestureDebugOverlay(
    controller: ChartController,
    debug: GestureDebug,
    liveRunning: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(250)
            tick++
        }
    }
    tick // read to force recomposition so the counters refresh

    Column {
        Text(
            "▤",
            Modifier
                .background(Color(0xCC1A2029))
                .clickable { onToggle() }
                .padding(horizontal = 5.dp, vertical = 3.dp),
            color = Color(0xFFFFD54F),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        if (expanded) {
            Text(
                buildString {
                    append("mode=").append(debug.mode)
                    append("  run=").append(debug.handlerRuns)
                    append("  ptr=").append(debug.pointerCount)
                    append('\n')
                    append("dx=").append("%.1f".format(debug.lastDx))
                    append("  zoom=").append("%.3f".format(debug.lastZoom))
                    append("  lp=").append(debug.longPressActive)
                    append("  live=").append(liveRunning)
                    append("  ins=").append(controller.state.bars.size)
                },
                Modifier.background(Color(0xCC1A2029)).padding(horizontal = 5.dp, vertical = 3.dp),
                color = Color(0xFFB0BEC5),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
