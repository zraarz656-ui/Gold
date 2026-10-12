package com.tradequest.chart

/**
 * One open position's entry point, in screen space, as the renderer sees it: the line's
 * ids (one per entry line) and its live P&L at the bid.
 */
data class EntryPoint(
    val ids: List<Long>,
    val y: Float,
    val pnl: Double?,
    val positive: Boolean,
)

/** Two or more entry points close together, shown as a single "N pos" tag. */
data class EntryGroup(
    val ids: List<Long>,
    val centerY: Float,
    val count: Int,
    val pnl: Double?,
    val positive: Boolean,
)

/**
 * Merges entry tags that sit within [MIN_GAP_DP] of each other vertically into one grouped
 * tag, so a stack of open positions does not turn the gutter into a wall of labels. Pure
 * float maths, so the "within 20dp merges" rule is proven in tests.
 */
object EntryGroups {

    /** Two entries closer than this (dp) merge into one group. */
    const val MIN_GAP_DP = 20f

    /**
     * Groups [points] whose vertical gap is below [gapPx]. Returns one group per run of two
     * or more consecutive entries; a lone point is left to the caller to draw as an ordinary
     * entry tag.
     */
    fun cluster(points: List<EntryPoint>, gapPx: Float): List<EntryGroup> {
        if (points.isEmpty()) return emptyList()
        val sorted = points.sortedBy { it.y }
        val out = ArrayList<EntryGroup>()
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size && sorted[j + 1].y - sorted[j].y < gapPx) j++
            val run = sorted.subList(i, j + 1)
            if (run.size >= 2) {
                val pnl = run.mapNotNull { it.pnl }.takeIf { it.isNotEmpty() }?.sum()
                out.add(
                    EntryGroup(
                        ids = run.flatMap { it.ids },
                        centerY = run.map { it.y }.average().toFloat(),
                        count = run.size,
                        pnl = pnl,
                        positive = (pnl ?: 0.0) >= 0.0,
                    ),
                )
            }
            i = j + 1
        }
        return out
    }
}
