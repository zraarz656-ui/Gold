package com.tradequest.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Small key/value settings kept in Room (survives process death by definition). */
class SettingsRepository(private val db: TradeQuestDatabase) {

    fun flow(key: String, default: String): Flow<String> =
        db.settingsDao().flow(key).map { it ?: default }

    suspend fun get(key: String, default: String): String = db.settingsDao().get(key) ?: default

    suspend fun put(key: String, value: String) = db.settingsDao().put(SettingEntity(key, value))

    companion object {
        const val IMPORT_DONE = "import_done"
        const val RISK_PERCENT = "risk_percent"
        const val LOTS = "last_lots"
    }
}
