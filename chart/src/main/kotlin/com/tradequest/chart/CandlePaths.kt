package com.tradequest.chart

import androidx.compose.ui.graphics.Path

/** Reusable paths for the candle bodies and wicks, rewound each frame. */
class CandlePaths {
    val upBody: Path = Path()
    val downBody: Path = Path()
    val upWick: Path = Path()
    val downWick: Path = Path()

    fun rewind() {
        upBody.rewind()
        downBody.rewind()
        upWick.rewind()
        downWick.rewind()
    }
}
