package com.tradequest.engine

/**
 * Builds higher-timeframe candles from a sorted list of 1-minute candles.
 *
 * Buckets are aligned by [MarketCalendar] (UTC for M15/H1, New York 17:00 rollover for
 * H4/D1/W1). Only buckets that actually contain candles are emitted: market closures
 * leave no flat filler candles.
 *
 * Aggregate values: `o` = first open, `h` = max high, `l` = min low, `c` = last close,
 * `v` = summed volume, `ts` = bucket start.
 */
object Aggregator {

    /**
     * Aggregate the whole [m1] series into [tf] candles.
     *
     * @param m1 candles sorted ascending by [Candle.ts].
     * @return aggregated candles sorted ascending; empty if [m1] is empty.
     */
    fun aggregate(m1: List<Candle>, tf: Timeframe): List<Candle> {
        if (tf == Timeframe.M1) return m1
        if (m1.isEmpty()) return emptyList()

        val out = ArrayList<Candle>()
        var bucketStart = MarketCalendar.bucketStart(tf, m1.first().ts)
        var o = m1.first().o
        var h = m1.first().h
        var l = m1.first().l
        var c = m1.first().c
        var v = m1.first().v

        for (i in 1 until m1.size) {
            val candle = m1[i]
            val start = MarketCalendar.bucketStart(tf, candle.ts)
            if (start == bucketStart) {
                if (candle.h > h) h = candle.h
                if (candle.l < l) l = candle.l
                c = candle.c
                v += candle.v
            } else {
                out.add(Candle(bucketStart, o, h, l, c, v))
                bucketStart = start
                o = candle.o
                h = candle.h
                l = candle.l
                c = candle.c
                v = candle.v
            }
        }
        out.add(Candle(bucketStart, o, h, l, c, v))
        return out
    }

    /**
     * Incrementally fold a single 1-minute [newM1] candle into an already-aggregated
     * [existing] series.
     *
     * If [newM1] belongs to the last existing bucket, that bucket is replaced by an
     * updated copy (its `ts`, `o` and `l`/`h` bounds are preserved); otherwise a new
     * bucket is appended. Purely a copy-on-write operation: [existing] is not mutated.
     *
     * @param existing previously aggregated candles for [tf], ascending.
     * @param newM1     the next 1-minute candle.
     * @return the updated aggregation.
     */
    fun updateForming(existing: List<Candle>, newM1: Candle, tf: Timeframe): List<Candle> {
        if (tf == Timeframe.M1) return existing + newM1

        val start = MarketCalendar.bucketStart(tf, newM1.ts)
        if (existing.isEmpty()) return listOf(Candle(start, newM1.o, newM1.h, newM1.l, newM1.c, newM1.v))

        val last = existing.last()
        return if (start == last.ts) {
            existing.dropLast(1) + last.copy(
                h = maxOf(last.h, newM1.h),
                l = minOf(last.l, newM1.l),
                c = newM1.c,
                v = last.v + newM1.v,
            )
        } else {
            existing + Candle(start, newM1.o, newM1.h, newM1.l, newM1.c, newM1.v)
        }
    }
}
