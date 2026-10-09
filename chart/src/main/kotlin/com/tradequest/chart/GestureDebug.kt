package com.tradequest.chart

/** Lightweight counters surfaced by the debug overlay. */
class GestureDebug {
    @Volatile var handlerRuns: Int = 0
    @Volatile var pointerCount: Int = 0
    @Volatile var lastDx: Float = 0f
    @Volatile var lastZoom: Float = 0f
    @Volatile var longPressActive: Boolean = false
    @Volatile var mode: String = "IDLE"

    fun resetFrame() {
        handlerRuns = 0
        lastDx = 0f
        lastZoom = 0f
    }
}
