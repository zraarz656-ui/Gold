package com.tradequest.chart

import com.tradequest.engine.Aggregator
import com.tradequest.engine.Candle
import com.tradequest.engine.Timeframe

/**
 * Caches the aggregated bars for each timeframe over a shared 1-minute series.
 *
 * [onNewM1] folds a freshly closed 1-minute candle into every cached timeframe, so the
 * forming bar on the active timeframe updates in place without a full re-aggregation.
 */
class TimeframeCache(m1: List<Candle>) {
    private val map = HashMap<Timeframe, List<Candle>>()

    init {
        map[Timeframe.M1] = m1
    }

    fun bars(tf: Timeframe): List<Candle> =
        map.getOrPut(tf) { Aggregator.aggregate(map.getValue(Timeframe.M1), tf) }

    fun onNewM1(candle: Candle): List<Candle> {
        for (tf in map.keys.toList()) {
            val existing = map.getValue(tf)
            map[tf] = if (tf == Timeframe.M1) existing + candle
            else Aggregator.updateForming(existing, candle, tf)
        }
        return map.getValue(Timeframe.M1)
    }

    fun allM1(): List<Candle> = map.getValue(Timeframe.M1)
}
