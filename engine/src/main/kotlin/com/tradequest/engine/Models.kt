package com.tradequest.engine

/**
 * A single OHLCV candle.
 *
 * @param ts UTC epoch milliseconds of the candle **open**.
 * @param o  open price
 * @param h  high price
 * @param l  low price
 * @param c  close price
 * @param v  volume
 */
data class Candle(
    val ts: Long,
    val o: Double,
    val h: Double,
    val l: Double,
    val c: Double,
    val v: Double,
)

/** Aggregation timeframe. */
enum class Timeframe { M1, M15, H1, H4, D1, W1 }

/** Trade direction. */
enum class Side { LONG, SHORT }

/** Order type. Market orders execute on the next candle; the others are pending. */
enum class OrderType { MARKET, BUY_LIMIT, BUY_STOP, SELL_LIMIT, SELL_STOP }

/** News importance. */
enum class Impact { LOW, MEDIUM, HIGH }

/**
 * A scheduled news release.
 *
 * @param ts    UTC epoch milliseconds of the release.
 * @param title human readable headline.
 * @param impact how much the release moves the market.
 */
data class NewsEvent(val ts: Long, val title: String, val impact: Impact)
