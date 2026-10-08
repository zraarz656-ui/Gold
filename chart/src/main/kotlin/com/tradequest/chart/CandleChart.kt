package com.tradequest.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.tradequest.engine.NewsEvent

/**
 * Interactive candlestick chart.
 *
 * Reads everything from [controller]; gestures (drag to pan, pinch to zoom, long-press
 * for the crosshair) mutate the controller, which drives recomposition.
 */
@Composable
fun CandleChart(
    controller: ChartController,
    modifier: Modifier = Modifier,
    displayOffsetMs: Long = 0L,
    crosshair: CrosshairInfo? = null,
    onCrosshairChange: (CrosshairInfo?) -> Unit = {},
    onNewsTap: (NewsEvent) -> Unit = {},
) {
    val density = LocalDensity.current.density
    val axisWidthPx = with(LocalDensity.current) { 60.dp.toPx() }
    val bottomAxisPx = with(LocalDensity.current) { 20.dp.toPx() }
    val textMeasurer = rememberTextMeasurer()
    val paths = remember { CandlePaths() }
    var size by remember { mutableStateOf(Size.Zero) }

    LaunchedEffect(displayOffsetMs) { controller.setDisplayOffset(displayOffsetMs) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { s ->
                size = Size(s.width.toFloat(), s.height.toFloat())
                controller.onLayout(density, maxOf(s.width - axisWidthPx, 1f))
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (pan != androidx.compose.ui.geometry.Offset.Zero) controller.pan(-pan.x)
                    if (zoom != 1f) controller.zoom(centroid.x, zoom)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { pos ->
                        val geo = geometryFor(controller.state, size, axisWidthPx, bottomAxisPx)
                        emitCrosshair(controller, pos.x, pos.y, geo, onCrosshairChange)
                    },
                    onTap = { pos ->
                        val geo = geometryFor(controller.state, size, axisWidthPx, bottomAxisPx)
                        if (pos.x > geo.right) {
                            controller.resetPriceScale()
                        } else if (crosshair != null) {
                            onCrosshairChange(null)
                        } else {
                            tapNews(controller, pos.x, geo, onNewsTap)
                        }
                    },
                )
            },
    ) {
        val geo = geometryFor(controller.state, size, axisWidthPx, bottomAxisPx)
        drawChart(controller.state, geo, textMeasurer, paths, crosshair, density)
        drawTimeAxis(controller.state, geo, textMeasurer, density)
    }
}

private fun emitCrosshair(
    controller: ChartController,
    x: Float,
    y: Float,
    geo: ChartGeometry,
    onChange: (CrosshairInfo?) -> Unit,
) {
    val idx = GestureMath.barIndexAt(x, controller.state.viewport, controller.state.barCount, geo.right)
    if (idx >= 0) {
        val c = controller.state.bars[idx]
        onChange(CrosshairInfo(c, ChartMath.yToPrice(y, geo.priceRange, geo.top, geo.bottom), x, y))
    }
}

private fun tapNews(
    controller: ChartController,
    x: Float,
    geo: ChartGeometry,
    onNewsTap: (NewsEvent) -> Unit,
) {
    val idx = GestureMath.barIndexAt(x, controller.state.viewport, controller.state.barCount, geo.right)
    if (idx < 0) return
    val ts = controller.state.bars[idx].ts
    controller.state.news.minByOrNull { kotlin.math.abs(it.ts - ts) }?.let(onNewsTap)
}
