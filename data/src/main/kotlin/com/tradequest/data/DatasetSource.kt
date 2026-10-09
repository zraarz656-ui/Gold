package com.tradequest.data

/** Where the candles in Room came from, shown in the Data panel. */
enum class DatasetSource(val label: String) {
    BUNDLED("bundled asset"),
    FAKE("fake generator"),

    /** Nothing imported and no usable asset — the app shows a data error screen. */
    MISSING("missing asset"),

    /** An asset was present but failed verification (meta/sha/rowCount/plausibility). */
    UNVERIFIED("unverified asset");

    companion object {
        fun fromId(id: String?): DatasetSource? = entries.firstOrNull { it.name == id }
    }
}
