package com.tradequest.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.tradequest.engine.NewsEvent
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Interactive candlestick chart.
 *
 * Reads everything from [controller] and drives it with a single pointer loop so the
 * gestures do not fight each other:
 *  - one finger on the plot: pan; release with speed: fling
 *  - two fingers: pinch to zoom the time axis (about the centroid)
 *  - one finger on the price gutter: drag the price range up/down
 *  - two fingers on the price gutter: scale the price range
 *  - long press: crosshair readout
 *  - double tap: auto-fit the price range
 *  - drag a draggable order line (SL/TP): edit that order
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
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    // Persisted across gestures so double taps can be recognised.
    var lastTapUpMs by remember { mutableStateOf(0L) }
    var lastTapPos by remember { mutableStateOf(Offset.Zero) }

    // Fling runs as a frame animation outside the (restricted) pointer coroutine.
    var fling by remember { mutableStateOf<Pair<Int, Float>?>(null) }
    var flingToken by remember { mutableStateOf(0) }

    val currentCrosshair by rememberUpdatedState(crosshair)
    val currentOnCrosshair by rememberUpdatedState(onCrosshairChange)
    val currentOnNewsTap by rememberUpdatedState(onNewsTap)
    val currentOnLineDrag by rememberUpdatedState(onLineDrag)

    LaunchedEffect(displayOffsetMs) { controller.setDisplayOffset(displayOffsetMs) }

    LaunchedEffect(fling) {
        val (_, startVelocity) = fling ?: return@LaunchedEffect
        var velocity = startVelocity
        var previous = withFrameNanos { it }
        var frames = 0
        while (abs(velocity) >= GestureMath.FLING_MIN_VELOCITY && frames < 180) {
            val now = withFrameNanos { it }
            val dt = ((now - previous) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.05f)
            previous = now
            controller.pan(-velocity * dt)
            velocity = GestureMath.decay(velocity)
            frames++
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { s ->
                canvasSize = Size(s.width.toFloat(), s.height.toFloat())
                controller.onLayout(density, maxOf(s.width - axisWidthPx, 1f))
            }
            .pointerInput(controller) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downPos = down.position
                    val onAxis = downPos.x > (canvasSize.width - axisWidthPx).coerceAtLeast(1f)
                    val longPressMs = viewConfiguration.longPressTimeoutMillis
                    val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis
                    val slop = viewConfiguration.touchSlop

                    // A draggable order line under the finger takes priority over panning.
                    val startGeo = geometryFor(controller.state, canvasSize, axisWidthPx, bottomAxisPx)
                    var dragTarget = if (onAxis) null else nearestLine(controller.state, startGeo, downPos.y)

                    var lastPos = downPos
                    var eventTime = down.uptimeMillis
                    var moved = false
                    var longPressFired = false
                    var lastSpan = 0f
                    val panSamples = ArrayList<Pair<Long, Float>>(16)
                    panSamples.add(down.uptimeMillis to downPos.x)

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        val primary = event.changes.firstOrNull { it.id == down.id }
                            ?: event.changes.firstOrNull() ?: break
                        eventTime = primary.uptimeMillis
                        val delta = primary.position - lastPos

                        if (pressed.size >= 2) {
                            lastSpan = applyPinch(controller, pressed.map { it.position }, lastSpan, onAxis)
                            moved = true
                            event.changes.forEach { it.consume() }
                        } else if (pressed.size == 1) {
                            lastSpan = 0f
                            if (delta.getDistance() > slop) moved = true
                            val target = dragTarget
                            when {
                                target != null -> {
                                    val geo = geometryFor(controller.state, canvasSize, axisWidthPx, bottomAxisPx)
                                    val price = ChartMath.yToPrice(primary.position.y, geo.priceRange, geo.top, geo.bottom)
                                    currentOnLineDrag(target.id, target.kind, price)
                                    event.changes.forEach { it.consume() }
                                }
                                moved && onAxis -> {
                                    val geo = geometryFor(controller.state, canvasSize, axisWidthPx, bottomAxisPx)
                                    val span = geo.priceRange.max - geo.priceRange.min
                                    controller.panPriceRange(-(delta.y / geo.height) * span)
                                    event.changes.forEach { it.consume() }
                                }
                                moved -> {
                                    controller.pan(-delta.x)
                                    panSamples.add(primary.uptimeMillis to primary.position.x)
                                    event.changes.forEach { it.consume() }
                                }
                                !longPressFired && down.uptimeMillis + longPressMs <= primary.uptimeMillis -> {
                                    longPressFired = true
                                    val geo = geometryFor(controller.state, canvasSize, axisWidthPx, bottomAxisPx)
                                    emitCrosshair(controller, primary.position.x, primary.position.y, geo, currentOnCrosshair)
                                }
                            }
                        }

                        lastPos = primary.position
                        if (event.changes.none { it.pressed }) break
                    }
                    dragTarget = null

                    val geo = geometryFor(controller.state, canvasSize, axisWidthPx, bottomAxisPx)
                    val isDouble = eventTime - lastTapUpMs < doubleTapMs &&
                        (downPos - lastTapPos).getDistance() < slop * 2f
                    when {
                        !moved && !longPressFired && isDouble && onAxis -> {
                            controller.autoFitPrice()
                            lastTapUpMs = 0L
                        }
                        !moved && !longPressFired && !onAxis -> {
                            lastTapUpMs = eventTime
                            lastTapPos = downPos
                            if (currentCrosshair != null) currentOnCrosshair(null) else tapNews(controller, downPos.x, geo, currentOnNewsTap)
                        }
                        !moved && !longPressFired -> {
                            lastTapUpMs = eventTime
                            lastTapPos = downPos
                        }
                        !onAxis && moved -> {
                            val v = GestureMath.velocity(panSamples)
                            if (GestureMath.shouldFling(v)) {
                                flingToken += 1
                                fling = flingToken to v
                            }
                        }
                    }
                }
            },
    ) {
        val geo = geometryFor(controller.state, canvasSize, axisWidthPx, bottomAxisPx)
        drawChart(controller.state, geo, textMeasurer, paths, crosshair, density)
        drawTimeAxis(controller.state, geo, textMeasurer, density)
        if (controller.state.marketClosed) drawMarketClosedBanner(controller.state, geo, textMeasurer)
    }
}

/** Pinch handling: time zoom on the plot, price scaling on the gutter. */
private fun applyPinch(
    controller: ChartController,
    positions: List<Offset>,
    previousSpan: Float,
    onAxis: Boolean,
): Float {
    val span = hypot(positions[1].x - positions[0].x, positions[1].y - positions[0].y)
    if (previousSpan > 0f && span > 0f) {
        val factor = span / previousSpan
        if (onAxis) controller.scalePriceRange(1.0 / factor.toDouble())
        else controller.zoom((positions[0].x + positions[1].x) / 2f, factor)
    }
    return span
}

/** The closest draggable line to [y], within a finger-sized band. */
private fun nearestLine(state: ChartState, geo: ChartGeometry, y: Float): ChartOrderLine? =
    state.orderLines
        .filter { it.draggable }
        .minByOrNull { abs(ChartMath.priceToY(it.price, geo.priceRange, geo.top, geo.bottom) - y) }
        ?.takeIf { abs(ChartMath.priceToY(it.price, geo.priceRange, geo.top, geo.bottom) - y) < LINE_HIT_PX }

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
    controller.state.news.minByOrNull { abs(it.ts - ts) }?.let(onNewsTap)
}
