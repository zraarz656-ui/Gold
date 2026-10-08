package com.tradequest.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.geometry.Offset
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
    onLineDrag: (Long, OrderLineKind, Double) -> Unit = { _, _, _ -> },
) {
    val density = LocalDensity.current.density
    val axisWidthPx = with(LocalDensity.current) { 60.dp.toPx() }
    val bottomAxisPx = with(LocalDensity.current) { 20.dp.toPx() }
    val textMeasurer = rememberTextMeasurer()
    val paths = remember { CandlePaths() }
    var size by remember { mutableStateOf(Size.Zero) }

    // Line being dragged: its id, its kind and the y where the drag began.
    var dragTarget by remember { mutableStateOf<Triple<Long, OrderLineKind, Float>?>(null) }

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
                    if (pan != Offset.Zero) controller.pan(-pan.x)
                    if (zoom != 1f) controller.zoom(centroid.x, zoom)
                }
            }
            .pointerInput(controller.state.orderLines) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val geo = geometryFor(controller.state, size, axisWidthPx, bottomAxisPx)
                        val hit = nearestLine(controller.state, geo, pos.y)
                        if (hit != null && hit.draggable) dragTarget = Triple(hit.id, hit.kind, pos.y)
                    },
                    onDrag = { change, _ ->
                        val t = dragTarget ?: return@detectDragGestures
                        val geo = geometryFor(controller.state, size, axisWidthPx, bottomAxisPx)
                        onLineDrag(t.first, t.second, ChartMath.yToPrice(change.position.y, geo.priceRange, geo.top, geo.bottom))
                    },
                    onDragEnd = { dragTarget = null },
                    onDragCancel = { dragTarget = null },
                )
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
        if (controller.state.marketClosed) drawMarketClosedBanner(controller.state, geo, textMeasurer)
    }
}

/** The closest draggable line to [y], within a finger-sized band. */
private fun nearestLine(state: ChartState, geo: ChartGeometry, y: Float): ChartOrderLine? =
    state.orderLines
        .filter { it.draggable }
        .minByOrNull { kotlin.math.abs(ChartMath.priceToY(it.price, geo.priceRange, geo.top, geo.bottom) - y) }
        ?.takeIf {
            kotlin.math.abs(ChartMath.priceToY(it.price, geo.priceRange, geo.top, geo.bottom) - y) < LINE_HIT_PX
        }

private const val LINE_HIT_PX = 28f

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
