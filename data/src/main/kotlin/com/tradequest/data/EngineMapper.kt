package com.tradequest.data

import com.tradequest.engine.Candle
import com.tradequest.engine.Side

/** Small conversions between Room rows and the engine's models. */
object EngineMapper {

    fun Candle1m.toEngine(): Candle = Candle(ts, o, h, l, c, tickVolume)

    fun TradeOrder.toPosition(): com.tradequest.engine.Position = com.tradequest.engine.Position(
        id = id,
        side = side,
        lots = lots,
        entryPrice = entryPrice ?: 0.0,
        openedAtTs = openedAt ?: 0L,
        sl = sl,
        tp = tp,
        trailDistance = trailingDist,
        pendingTrail = pendingTrail,
    )

    fun TradeOrder.toOrder(): com.tradequest.engine.Order = com.tradequest.engine.Order(
        id = id,
        side = side,
        type = type,
        lots = lots,
        price = entryPrice,
        sl = sl,
        tp = tp,
        placedAtTs = openedAt ?: 0L,
        trailDistance = trailingDist,
    )

    fun Side.sign(): Int = if (this == Side.LONG) 1 else -1
}
