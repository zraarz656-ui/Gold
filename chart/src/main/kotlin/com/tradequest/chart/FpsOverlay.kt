package com.tradequest.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Frame-rate summary surfaced by the debug overlay. */
data class FpsStats(val fps: Int, val frameMs: Float, val worstMs: Float)

/** Tracks frame times every frame and reports a rolling average plus the worst in the last second. */
@Composable
fun rememberFpsStats(): State<FpsStats> {
    val stats = remember { mutableStateOf(FpsStats(0, 0f, 0f)) }
    LaunchedEffect(Unit) {
        var frames = 0
        var worst = 0f
        var windowStart = withFrameNanos { it }
        var previous = windowStart
        while (true) {
            val now = withFrameNanos { it }
            val frameMs = (now - previous) / 1_000_000f
            previous = now
            frames++
            worst = maxOf(worst, frameMs)
            val elapsed = (now - windowStart) / 1_000_000f
            if (elapsed >= 1_000f) {
                stats.value = FpsStats(frames, elapsed / frames, worst)
                frames = 0
                worst = 0f
                windowStart = now
            }
        }
    }
    return stats
}

@Composable
fun FpsOverlay(modifier: Modifier = Modifier) {
    val stats = rememberFpsStats().value
    Text(
        "${stats.fps} fps  ${"%.1f".format(stats.frameMs)}ms  max ${"%.1f".format(stats.worstMs)}",
        modifier
            .background(Color(0x88000000))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = Color(0xFF00E676),
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
    )
}
