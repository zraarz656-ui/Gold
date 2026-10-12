package com.tradequest.engine

/**
 * Account equity recorded at the close of a candle.
 *
 * @param ts         candle timestamp the snapshot belongs to.
 * @param equity     balance plus floating PnL.
 * @param balance    realised balance.
 * @param usedMargin margin locked by open positions.
 */
data class EquitySnapshot(
    val ts: Long,
    val equity: Double,
    val balance: Double,
    val usedMargin: Double,
)
