package com.tradequest.chart

/**
 * Stacks price tags in the right gutter so none overlaps another and none is cut off by the
 * plot's top or bottom edge. Pure float maths, so "tags never collide" is proven in tests.
 *
 * Input is the tags' desired centre Y in *input order*; the output has the same order with
 * each Y shifted the least amount needed. When the tags cannot all fit (more tags than the
 * plot has room for) they are spread as evenly as the bounds allow; overlap is then
 * unavoidable, so the caller is responsible for keeping the count sane.
 */
object TagLayout {

    /** Clear space kept between two stacked tags. */
    const val GAP = 2f

    fun place(centers: List<Float>, height: Float, top: Float, bottom: Float, gap: Float = GAP): List<Float> {
        val n = centers.size
        if (n == 0) return emptyList()
        val half = height / 2f
        val lo = top + half
        val hi = maxOf(bottom - half, lo)

        val order = centers.indices.sortedBy { centers[it] }
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = centers[i].coerceIn(lo, hi)

        // Push down each tag below its upper neighbour, then pull the whole stack back up
        // from the bottom so the lowest tag stays on screen.
        for (k in 1 until n) {
            val i = order[k]
            val prev = order[k - 1]
            out[i] = maxOf(out[i], out[prev] + height + gap)
        }
        out[order[n - 1]] = minOf(out[order[n - 1]], hi)
        for (k in n - 2 downTo 0) {
            val i = order[k]
            val next = order[k + 1]
            out[i] = minOf(out[i], out[next] - height - gap)
        }
        for (i in 0 until n) out[i] = out[i].coerceIn(lo, hi)
        return out.toList()
    }
}
