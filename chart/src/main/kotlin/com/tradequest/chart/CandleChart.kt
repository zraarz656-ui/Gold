package com.tradequest.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.tradequest.engine.NewsEvent
import kotlin.math.abs

/**
 * Interactive candlestick chart, ported from Phase 2.
 *
 * One pointer loop drives every gesture so they cannot fight each other:
 *  - one finger on the plot: pan; the release velocity flings with decay
 *  - two fingers: pinch to zoom the time axis about the centroid
 *  - one finger on the price gutter (or a mostly-vertical drag): drag the price range
 *  - two fingers on the price gutter: scale the price range
 *  - long press: crosshair readout that tracks the finger
 *  - double tap on the gutter: auto-fit the price range
 *  - tap next to the time axis: open the nearest news; tap on the plot: clear the crosshair
 *  - drag an order line's tag/body or a "+SL"/"+TP" handle (Phase 3): edit that level
 *
 * The Phase 3 additions are [onLevelOutcome] and the DRAG_LEVEL branch; every other
 * gesture mirrors the recovered Phase 2 behaviour exactly.
 */
@Composable
fun CandleChart(
    controller: ChartController,
    modifier: Modifier = Modifier,
    displayOffsetMs: Long = 0L,
    debug: GestureDebug? = null,
    crosshair: CrosshairInfo? = null,
    onCrosshairChange: (CrosshairInfo?) -> Unit = {},
    onNewsTap: (NewsEvent) -> Unit = {},
    onLevelOutcome: (LevelOutcome) -> Unit = {},
    onEntryGroupTap: () -> Unit = {},
) {
    val density = LocalDensity.current.density
    val bottomAxisPx = with(LocalDensity.current) { 20.dp.toPx() }
    val textMeasurer = rememberTextMeasurer()
    val paths = remember { CandlePaths() }
    // One measurer shared by the renderer and the hit test, so a tag's drawn width is
    // exactly the width the hit test uses.
    val tagMeasure = remember(textMeasurer) {
        TagTextMeasure { text, sp ->
            textMeasurer.measure(
                text,
                TextStyle(fontSize = sp.sp, fontWeight = FontWeight.Bold),
            ).size.width.toFloat()
        }
    }
    // The right gutter is measured from the widest price text the tag must hold — the last
    // close and the visible range's extremes, so the tag fits at any live price — plus 8dp
    // (4dp each side). Keyed on the labels so a same-width tick does not restart the loop.
    val lastClose = controller.state.bars.lastOrNull()?.c ?: 0.0
    val priceRange = controller.currentPriceRange()
    val priceLabels = listOf(lastClose, priceRange.min, priceRange.max).map { formatPrice(it) }
    val axisWidthPx = remember(density, tagMeasure, controller.state.labelSize, priceLabels) {
        val (pillSp, gridSp) = ChartGutter.sampleSizes(controller.state.labelScale)
        ChartGutter.widthPx(
            priceLabels.flatMap { listOf(it to pillSp, it to gridSp) },
            tagMeasure,
            density,
        )
    }

    var size by remember { mutableStateOf(Size.Zero) }
    var longPressActive by remember { mutableStateOf(false) }
    var flingVelocity by remember { mutableStateOf(0f) }
    var flingToken by remember { mutableStateOf(0L) }

    val currentController by rememberUpdatedState(controller)
    val currentDebug by rememberUpdatedState(debug)
    val currentOnCrosshair by rememberUpdatedState(onCrosshairChange)
    val currentOnNewsTap by rememberUpdatedState(onNewsTap)
    val currentOnLevelOutcome by rememberUpdatedState(onLevelOutcome)
    val currentOnEntryGroupTap by rememberUpdatedState(onEntryGroupTap)

    LaunchedEffect(displayOffsetMs) { controller.setDisplayOffset(displayOffsetMs) }

    LaunchedEffect(flingToken) {
        if (flingToken == 0L) return@LaunchedEffect
        var velocity = flingVelocity
        var previous = withFrameNanos { it }
        var frames = 0
        while (abs(velocity) >= GestureMath.FLING_MIN_VELOCITY && frames < 180) {
            val now = withFrameNanos { it }
            val dt = ((now - previous) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.05f)
            previous = now
            controller.pan(velocity * dt)
            velocity = GestureMath.decay(velocity)
            frames++
        }
    }

    Canvas(
        modifier = modifier
            .background(controller.state.theme.background)
            .onSizeChanged { s ->
                size = Size(s.width.toFloat(), s.height.toFloat())
                controller.onLayout(density, maxOf(s.width - axisWidthPx, 1f))
            }
            .pointerInput(currentController, axisWidthPx, bottomAxisPx) {
                var lastTapUpMs = 0L
                var lastTapPos = Offset.Zero

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    val longPressMs = viewConfiguration.longPressTimeoutMillis
                    val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis
                    val canvas = this@pointerInput.size.toSize()
                    val onAxis = down.position.x > (canvas.width - axisWidthPx)

                    var lastX = down.position.x
                    var lastY = down.position.y
                    var moved = 0f
                    var lpFired = false
                    var pinching = false
                    var panning = false
                    var scaleStarted = false
                    var scaleStartMin = 0.0
                    var scaleStartMax = 0.0
                    // Tags and handles are draggable even inside the price gutter, so the hit
                    // test runs everywhere; a miss simply falls through to pan/scale as before.
                    val hitGeo = geometryFor(controller.state, canvas, axisWidthPx, bottomAxisPx)
                    val levelHit: LevelHit? = LevelHitTest.hit(
                        controller.state,
                        hitGeo,
                        down.position.x, down.position.y,
                        controller.state.selectedEntryId,
                        tagMeasure,
                    )
                    var levelDragging = false
                    val panSamples = ArrayList<Pair<Long, Float>>(16)
                    panSamples.add(down.uptimeMillis to down.position.x)

                    while (true) {
                        val event = awaitPointerEvent()
                        currentDebug?.let { it.handlerRuns += 1 }
                        val change: PointerInputChange? = event.changes.firstOrNull { it.id == down.id }
                        val contacts = event.changes.count { it.pressed }
                        currentDebug?.pointerCount = contacts

                        if (change == null || !change.pressed) break
                        val geo = geometryFor(controller.state, canvas, axisWidthPx, bottomAxisPx)
                        moved = maxOf(moved, (change.position - down.position).getDistance())

                        // A level drag (Phase 3) outranks pan/crosshair: once the finger is on a
                        // line it is consumed for the rest of the gesture. A "+" handle shows its
                        // pinned preview at once; an existing line starts moving only past slop.
                        val hit = levelHit
                        if (hit != null && contacts == 1) {
                            currentDebug?.mode = "DRAG_LEVEL"
                            change.consume()
                            if (hit is LevelHit.CloseBox || hit is LevelHit.EntryTag) {
                                // These only act on release; no preview while holding them.
                                lastX = change.position.x
                                lastY = change.position.y
                                continue
                            }
                            if (!levelDragging && (hit is LevelHit.Handle || moved > slop)) {
                                levelDragging = true
                                controller.beginLevelDrag(hit)
                            }
                            if (levelDragging) {
                                val price = ChartMath.yToPrice(change.position.y, geo.priceRange, geo.plot)
                                controller.updateLevelDrag(price, change.position.x, change.position.y)
                            }
                            lastX = change.position.x
                            lastY = change.position.y
                            continue
                        }

                        // Long press -> crosshair that tracks the finger.
                        if (!lpFired && contacts == 1 && !onAxis && moved <= slop &&
                            change.uptimeMillis - down.uptimeMillis >= longPressMs
                        ) {
                            lpFired = true
                            longPressActive = true
                            currentDebug?.longPressActive = true
                            emitCrosshair(controller, change.position.x, change.position.y, geo, currentOnCrosshair)
                            change.consume()
                        } else if (longPressActive) {
                            change.consume()
                            emitCrosshair(controller, change.position.x, change.position.y, geo, currentOnCrosshair)
                        } else if (contacts >= 2) {
                            pinching = true
                            if (onAxis) {
                                // Two fingers on the gutter scale the price range.
                                currentDebug?.mode = "PRICE_SCALE"
                                if (!scaleStarted) {
                                    scaleStarted = true
                                    scaleStartMin = geo.priceRange.min
                                    scaleStartMax = geo.priceRange.max
                                }
                                controller.setManualPriceRange(scaleStartMin, scaleStartMax)
                                val factor = GestureMath.priceScaleFactor(
                                    change.position.y - down.position.y, geo.plot.height,
                                )
                                controller.scalePriceRange(factor)
                                currentDebug?.lastZoom = factor.toFloat()
                            } else {
                                currentDebug?.mode = "PINCH"
                                val zoom = event.calculateZoom()
                                val centroid = event.calculateCentroid()
                                currentDebug?.lastZoom = zoom
                                if (zoom != 1f && centroid != Offset.Unspecified) controller.zoom(centroid.x, zoom)
                            }
                            event.changes.forEach { it.consume() }
                        } else if (onAxis) {
                            currentDebug?.mode = "PRICE_SCALE"
                            if (!scaleStarted) {
                                scaleStarted = true
                                scaleStartMin = geo.priceRange.min
                                scaleStartMax = geo.priceRange.max
                            }
                            controller.setManualPriceRange(scaleStartMin, scaleStartMax)
                            val totalDy = change.position.y - down.position.y
                            val factor = GestureMath.priceScaleFactor(totalDy, geo.plot.height)
                            controller.scalePriceRange(factor)
                            currentDebug?.lastZoom = factor.toFloat()
                            event.changes.forEach { it.consume() }
                        } else if (controller.state.viewport.manualPriceScale &&
                            abs(change.position.y - lastY) > 0f &&
                            abs(change.position.y - lastY) > abs(change.position.x - lastX)
                        ) {
                            currentDebug?.mode = "PRICE_DRAG"
                            val perPx = geo.priceRange.span / maxOf(geo.plot.height, 1f)
                            controller.panPriceRange((change.position.y - lastY) * perPx)
                            event.changes.forEach { it.consume() }
                        } else {
                            val dx = change.position.x - lastX
                            if (dx != 0f) {
                                panning = true
                                currentDebug?.mode = "PAN"
                                currentDebug?.lastDx = dx
                                controller.pan(-dx)
                                panSamples.add(change.uptimeMillis to change.position.x)
                            }
                        }
                        lastX = change.position.x
                        lastY = change.position.y
                    }

                    if (levelHit != null && levelDragging) {
                        val geo = geometryFor(controller.state, canvas, axisWidthPx, bottomAxisPx)
                        if (levelHit is LevelHit.CloseBox) {
                            controller.cancelLevelDrag()
                            currentOnLevelOutcome(LevelOutcome.Clear(levelHit.lineId, levelHit.kind))
                        } else {
                            val price = ChartMath.yToPrice(lastY, geo.priceRange, geo.plot)
                            val outcome = controller.endLevelDrag(price, controller.state.overlay.bid, controller.state.overlay.spread)
                            currentOnLevelOutcome(outcome)
                        }
                    } else if (levelHit is LevelHit.CloseBox && moved <= slop) {
                        // A tap on the "x": clear that level.
                        currentOnLevelOutcome(LevelOutcome.Clear(levelHit.lineId, levelHit.kind))
                    } else if (levelHit is LevelHit.EntryGroupTag && moved <= slop) {
                        // A tap on a merged "N pos" tag jumps to the Positions tab.
                        currentOnEntryGroupTap()
                    } else if (levelHit is LevelHit.EntryTag && moved <= slop) {
                        // A tap on an open position selects it (showing its "+SL"/"+TP" handles)
                        // or, when already selected, deselects it. A pending order's tag keeps
                        // its old meaning: tapping it strips the pending order's levels.
                        val line = controller.state.orderLines.firstOrNull { it.id == levelHit.lineId }
                        if (line?.kind == OrderLineKind.ENTRY) {
                            val current = controller.state.selectedEntryId
                            controller.selectEntry(if (current == levelHit.lineId) null else levelHit.lineId)
                        } else {
                            currentOnLevelOutcome(LevelOutcome.Clear(levelHit.lineId, OrderLineKind.SL))
                            currentOnLevelOutcome(LevelOutcome.Clear(levelHit.lineId, OrderLineKind.TP))
                        }
                    } else if (levelHit != null) {
                        controller.cancelLevelDrag()
                    }

                    if (!panning && !pinching && !lpFired && !levelDragging && levelHit == null && moved <= slop) {
                        val now = down.uptimeMillis
                        val isDouble = (now - lastTapUpMs) <= doubleTapMs &&
                            (down.position - lastTapPos).getDistance() <= 2f * slop
                        if (isDouble) {
                            if (onAxis) controller.autoFitPrice()
                            lastTapUpMs = 0L
                        } else {
                            lastTapUpMs = now
                            lastTapPos = down.position
                            if (!onAxis) {
                                // Tap next to the time axis opens the nearest news; elsewhere clears the crosshair.
                                if (down.position.y >= canvas.height - bottomAxisPx) {
                                    tapNews(controller, down.position.x, geometryFor(controller.state, canvas, axisWidthPx, bottomAxisPx), currentOnNewsTap)
                                } else {
                                    currentOnCrosshair(null)
                                }
                            }
                        }
                    }

                    // A pan that ends with speed flings on.
                    if (panning && !pinching && !lpFired && !onAxis) {
                        val velocity = -GestureMath.velocity(panSamples)
                        if (GestureMath.shouldFling(velocity)) {
                            flingVelocity = velocity
                            flingToken += 1
                        }
                    }
                    if (longPressActive) {
                        longPressActive = false
                        currentDebug?.longPressActive = false
                    }
                }
            },
    ) {
        val geo = geometryFor(controller.state, size, axisWidthPx, bottomAxisPx)
        drawChart(controller.state, geo, textMeasurer, paths, crosshair, density, tagMeasure)
        drawTimeAxis(controller.state, geo, textMeasurer, density)
        if (controller.state.marketClosed) drawMarketClosedBanner(controller.state, geo, textMeasurer)
    }
}

private fun emitCrosshair(
    controller: ChartController,
    x: Float,
    y: Float,
    geo: ChartGeometry,
    onChange: (CrosshairInfo?) -> Unit,
) {
    val idx = GestureMath.barIndexAt(x, controller.state.viewport, controller.state.barCount, geo.plot.right)
    if (idx >= 0) {
        val c = controller.state.bars[idx]
        onChange(CrosshairInfo(c, ChartMath.yToPrice(y, geo.priceRange, geo.plot), x, y))
    }
}

private fun tapNews(
    controller: ChartController,
    x: Float,
    geo: ChartGeometry,
    onNewsTap: (NewsEvent) -> Unit,
) {
    val idx = GestureMath.barIndexAt(x, controller.state.viewport, controller.state.barCount, geo.plot.right)
    if (idx < 0) return
    val ts = controller.state.bars[idx].ts
    controller.state.news.minByOrNull { abs(it.ts - ts) }?.let(onNewsTap)
}
