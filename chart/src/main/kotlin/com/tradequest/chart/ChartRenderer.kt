package com.tradequest.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import com.tradequest.engine.Candle
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

const val PRICE_LABEL_MIN_GAP_DP = 20.0f
const val TIME_LABEL_MIN_GAP_DP = 64.0f

/** Plot rectangle + resolved price range + visible bar window for one frame. */
fun geometryFor(state: ChartState, sizePx: Size, axisWidthPx: Float, bottomAxisPx: Float): ChartGeometry {
    val plot = ChartMath.plotRect(sizePx.width, sizePx.height, axisWidthPx, bottomAxisPx)
    val visible = ChartMath.visibleRange(state.viewport, plot.right, state.barCount, 2)
    return ChartGeometry(plot.left, plot.top, plot.right, plot.bottom, resolvePriceRange(state, visible), visible)
}

/** Auto-fit the visible bars, or honour the manual range when the user has pinned one. */
fun resolvePriceRange(state: ChartState, visible: IntRange): PriceRange {
    val vp = state.viewport
    if (vp.manualPriceScale) {
        return ChartMath.clampManualRange(
            vp.manualPriceMin ?: 0.0,
            vp.manualPriceMax ?: 1.0,
            ChartMath.fullExtent(state.bars),
        )
    }
    val e = ChartMath.dataExtent(state.bars, visible) ?: return PriceRange(0.0, 1.0)
    return ChartMath.autoFitRange(e.min, e.max)
}

fun DrawScope.drawChart(
    state: ChartState,
    geo: ChartGeometry,
    textMeasurer: TextMeasurer,
    paths: CandlePaths,
    crosshair: CrosshairInfo?,
    density: Float = 1f,
) {
    val theme = state.theme
    val priceTicks = ChartMath.niceTicks(geo.priceRange.min, geo.priceRange.max, 6)
    val plot = geo.plot

    // Everything that belongs to the plot is clipped to it, so no layer can bleed over the
    // price gutter, the time axis or its neighbours.
    clipRect(plot.left, plot.top, plot.right, plot.bottom) {
        for (p in priceTicks) {
            val y = ChartMath.priceToY(p, geo.priceRange, plot)
            drawLine(theme.grid, Offset(plot.left, y), Offset(plot.right, y), 1f)
        }
        drawLine(theme.grid, Offset(plot.right, plot.top), Offset(plot.right, plot.bottom), 1f)

        drawMarketBands(state, geo)
        drawCandles(state, geo, paths)
        drawIndicatorHook(state, geo)
        drawDrawingsHook(state, geo)
        drawOrderLinesHook(state, geo, textMeasurer)

        if (state.bars.lastOrNull() != null) drawCurrentPriceLine(state, geo)
        if (crosshair != null) drawCrosshair(state, geo, crosshair)
    }

    val last = state.bars.lastOrNull()
    val currentY = last?.let { ChartMath.priceToY(it.c, geo.priceRange, plot) }
    val crosshairY = crosshair?.let { it.y.coerceIn(plot.top, plot.bottom) }
    val occupied = listOfNotNull(currentY, crosshairY).map { it.coerceIn(plot.top + 7f, plot.bottom - 7f) }
    val minGapPx = density * PRICE_LABEL_MIN_GAP_DP

    // Price labels live in the right gutter; clip them there so they never overlap the plot.
    clipRect(plot.right, 0f, size.width, size.height) {
        for (p in priceTicks) {
            val y = ChartMath.priceToY(p, geo.priceRange, plot)
            if (!ChartMath.collidesWithAny(y, occupied, minGapPx)) {
                drawAxisLabel(textMeasurer, theme, formatPrice(p), plot.right + 4f, y - 6f)
            }
        }
        if (last != null && currentY != null) {
            drawPriceLabel(textMeasurer, theme, geo, last.c, currentY, theme.currentPrice)
        }
        if (crosshair != null) {
            val cy = crosshair.y.coerceIn(plot.top, plot.bottom)
            drawPriceLabel(textMeasurer, theme, geo, ChartMath.yToPrice(cy, geo.priceRange, plot), cy, theme.crosshair)
        }
    }
}

/** Shade the weekend gaps (a candle gap wider than two bars means the market was shut). */
private fun DrawScope.drawMarketBands(state: ChartState, geo: ChartGeometry) {
    val theme = state.theme
    val vp = state.viewport
    val span = ChartController.spanMs(state.timeframe)
    val bars = state.bars
    if (geo.visible.isEmpty()) return
    for (i in maxOf(1, geo.visible.first)..geo.visible.last) {
        if (i - 1 < 0) continue
        val gap = bars[i].ts - bars[i - 1].ts
        if (gap > 2 * span) {
            val center = ChartMath.indexToX(i - 0.5f, vp)
            val w = maxOf(vp.candleWidthPx * 0.35f, 6f)
            val left = center - w / 2f
            if (left < geo.plot.right && left + w > geo.plot.left) {
                drawRect(theme.marketClosed, Offset(left, geo.plot.top), Size(w, geo.plot.height))
                drawLine(theme.grid, Offset(center, geo.plot.top), Offset(center, geo.plot.bottom), 1f)
            }
        }
    }
}

private fun DrawScope.drawCandles(state: ChartState, geo: ChartGeometry, paths: CandlePaths) {
    val theme = state.theme
    val vp = state.viewport
    paths.rewind()
    val halfW = maxOf(0.5f, vp.candleWidthPx / 2f - 0.5f)
    for (i in geo.visible.first..geo.visible.last) {
        val c = state.bars[i]
        val x = ChartMath.indexToX(i.toFloat(), vp)
        if (x < geo.plot.left - vp.candleWidthPx || x > geo.plot.right + vp.candleWidthPx) continue
        val up = c.c >= c.o
        val yH = ChartMath.priceToY(c.h, geo.priceRange, geo.plot)
        val yL = ChartMath.priceToY(c.l, geo.priceRange, geo.plot)
        val yO = ChartMath.priceToY(c.o, geo.priceRange, geo.plot)
        val yC = ChartMath.priceToY(c.c, geo.priceRange, geo.plot)
        val wick = if (up) paths.upWick else paths.downWick
        wick.moveTo(x, yH)
        wick.lineTo(x, yL)
        val bodyTop = min(yO, yC)
        val bodyH = maxOf(1f, maxOf(yO, yC) - bodyTop)
        val body = if (up) paths.upBody else paths.downBody
        body.addRect(Rect(x - halfW, bodyTop, x + halfW, bodyTop + bodyH))
    }
    drawPath(paths.upWick, theme.upWick, style = Stroke(1f))
    drawPath(paths.downWick, theme.downWick, style = Stroke(1f))
    drawPath(paths.upBody, theme.up)
    drawPath(paths.downBody, theme.down)
}

// Extension points for Phase 3 overlays (indicators, drawings, order lines).
private fun DrawScope.drawIndicatorHook(state: ChartState, geo: ChartGeometry) {}
private fun DrawScope.drawDrawingsHook(state: ChartState, geo: ChartGeometry) {}

/** Draw the order/SL/TP lines and entry/exit markers supplied by the trading layer. */
private fun DrawScope.drawOrderLinesHook(state: ChartState, geo: ChartGeometry, textMeasurer: TextMeasurer) {
    if (state.orderLines.isEmpty() && state.markers.isEmpty()) return
    for (line in state.orderLines) {
        val y = ChartMath.priceToY(line.price, geo.priceRange, geo.plot)
        if (y < geo.plot.top - 2f || y > geo.plot.bottom + 2f) continue
        val color = line.color()
        drawDashedLine(color, Offset(geo.plot.left, y), Offset(geo.plot.right, y))
        val label = if (line.pnlText != null) line.label + "  " + line.pnlText else line.label
        drawTag(textMeasurer, geo, label, y, color)
    }
    for (m in state.markers) {
        val x = ChartMath.indexToX(m.barIndex.toFloat(), state.viewport)
        if (x < geo.plot.left - 8f || x > geo.plot.right + 8f) continue
        val y = ChartMath.priceToY(m.price, geo.priceRange, geo.plot)
        drawMarker(x, y, m.entry, m.long)
    }
}

private fun ChartOrderLine.color(): Color = when (kind) {
    OrderLineKind.ENTRY -> Color(0xFF42A5F5)
    OrderLineKind.SL -> Color(0xFFEF5350)
    OrderLineKind.TP -> Color(0xFF26A69A)
    OrderLineKind.PENDING -> Color(0xFFFFB300)
}

private fun DrawScope.drawTag(textMeasurer: TextMeasurer, geo: ChartGeometry, text: String, y: Float, color: Color) {
    val yc = y.coerceIn(geo.plot.top + 7f, geo.plot.bottom - 7f)
    drawRect(color, Offset(geo.plot.left + 1f, yc - 7f), Size(120f, 14f))
    drawText(textMeasurer, text, Offset(geo.plot.left + 4f, yc - 6f), TextStyle(Color.White, 10.sp))
}

/** A small triangle at an entry/exit point. */
private fun DrawScope.drawMarker(x: Float, y: Float, entry: Boolean, long: Boolean) {
    val size = 7f
    val path = Path()
    if (entry) {
        if (long) {
            path.moveTo(x, y - size)
            path.lineTo(x - size, y - size * 2.2f)
            path.lineTo(x + size, y - size * 2.2f)
        } else {
            path.moveTo(x, y + size)
            path.lineTo(x - size, y + size * 2.2f)
            path.lineTo(x + size, y + size * 2.2f)
        }
    } else {
        if (long) {
            path.moveTo(x, y + size)
            path.lineTo(x - size, y + size * 2.2f)
            path.lineTo(x + size, y + size * 2.2f)
        } else {
            path.moveTo(x, y - size)
            path.lineTo(x - size, y - size * 2.2f)
            path.lineTo(x + size, y - size * 2.2f)
        }
    }
    path.close()
    drawPath(path, if (long) Color(0xFF26A69A) else Color(0xFFEF5350))
}

/** The current-price dashed line; its price label is drawn in the gutter clip. */
private fun DrawScope.drawCurrentPriceLine(state: ChartState, geo: ChartGeometry) {
    val last = state.bars.lastOrNull() ?: return
    val y = ChartMath.priceToY(last.c, geo.priceRange, geo.plot)
    drawDashedLine(state.theme.currentPrice, Offset(geo.plot.left, y), Offset(geo.plot.right, y))
}

private fun DrawScope.drawCrosshair(
    state: ChartState,
    geo: ChartGeometry,
    crosshair: CrosshairInfo,
) {
    val theme = state.theme
    val cx = crosshair.x.coerceIn(geo.plot.left, geo.plot.right)
    val cy = crosshair.y.coerceIn(geo.plot.top, geo.plot.bottom)
    drawDashedLine(theme.crosshair, Offset(cx, geo.plot.top), Offset(cx, geo.plot.bottom))
    drawDashedLine(theme.crosshair, Offset(geo.plot.left, cy), Offset(geo.plot.right, cy))
}

private fun DrawScope.drawDashedLine(color: Color, a: Offset, b: Offset) {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len = hypot(dx, dy)
    if (len <= 0f) return
    val ux = dx / len
    val uy = dy / len
    var t = 0f
    while (t < len) {
        val end = min(t + 6f, len)
        drawLine(color, Offset(a.x + ux * t, a.y + uy * t), Offset(a.x + ux * end, a.y + uy * end), 1f)
        t += 6f + 5f
    }
}

private fun DrawScope.drawAxisLabel(textMeasurer: TextMeasurer, theme: ChartTheme, text: String, x: Float, y: Float) {
    drawText(textMeasurer, text, Offset(x, y), TextStyle(theme.axisText, 10.sp))
}

private fun DrawScope.drawPriceLabel(
    textMeasurer: TextMeasurer,
    theme: ChartTheme,
    geo: ChartGeometry,
    price: Double,
    y: Float,
    color: Color,
) {
    val yClamped = y.coerceIn(geo.plot.top + 7f, geo.plot.bottom - 7f)
    drawRect(color, Offset(geo.plot.right + 1f, yClamped - 7f), Size(56f, 14f))
    drawText(textMeasurer, formatPrice(price), Offset(geo.plot.right + 4f, yClamped - 6f), TextStyle(Color.White, 10.sp))
}

fun formatPrice(p: Double): String {
    val v = Math.rint(p * 100.0) / 100.0
    val s = v.toString()
    return if (s.contains('.')) s else "$s.0"
}

fun DrawScope.drawTimeAxis(state: ChartState, geo: ChartGeometry, textMeasurer: TextMeasurer, density: Float = 1f) {
    val theme = state.theme
    val vp = state.viewport
    val bars = state.bars
    if (bars.isEmpty()) return
    val axisTop = geo.plot.bottom
    clipRect(geo.plot.left, axisTop, geo.plot.right, size.height) {
        drawLine(theme.grid, Offset(geo.plot.left, axisTop), Offset(geo.plot.right, axisTop), 1f)
        val displayTs = bars.map { it.ts + state.displayOffsetMs }
        val minSpacingPx = density * TIME_LABEL_MIN_GAP_DP
        val ticks = ChartMath.timeAxisTicks(
            displayTs, geo.visible, state.timeframe, vp.scrollIndex, vp.candleWidthPx, minSpacingPx,
        )
        for (t in ticks) {
            val x = ChartMath.indexToX(t.index.toFloat(), vp)
            if (x < geo.plot.left - 6f || x > geo.plot.right) continue
            // Draw the ticks the label marks: a short vertical line at the tick, then the text.
            drawLine(theme.grid, Offset(x, axisTop), Offset(x, axisTop + 4f), 1f)
            drawAxisLabel(textMeasurer, theme, formatTimeLabel(t.displayTs, state.timeframe), x + 3f, axisTop + 4f)
        }
    }
}

/** Centre banner shown while the replayed market is shut. */
fun DrawScope.drawMarketClosedBanner(state: ChartState, geo: ChartGeometry, textMeasurer: TextMeasurer) {
    val text = "Market closed"
    val cx = (geo.plot.left + geo.plot.right) / 2f
    val cy = (geo.plot.top + geo.plot.bottom) / 2f
    drawRect(Color(0xCC1A1F27), Offset(cx - 92f, cy - 20f), Size(184f, 40f))
    drawText(textMeasurer, text, Offset(cx - 78f, cy - 10f), TextStyle(Color(0xFFFFB300), 16.sp))
}
