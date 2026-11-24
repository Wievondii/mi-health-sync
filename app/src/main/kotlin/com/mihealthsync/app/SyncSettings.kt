package com.mihealthsync.app

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.preferences by preferencesDataStore(name = "sync_preferences")

internal data class Settings(
    val automatic: Boolean = true,
    val interval: Int = 30,
    val wifiOnly: Boolean = false,
    val firstDays: Int = 7,
    val overlapHours: Int = 48,
    val enabled: Set<Metric> = Metric.entries.toSet(),
    val retryUntilMillis: Long = 0,
)

internal class SyncSettings(context: Context) {
    private val store = context.preferences
    val values = store.data.map(::decode)

    suspend fun current(): Settings = values.first()

    suspend fun update(change: (Settings) -> Settings) {
        store.edit { preferences ->
            val next = change(decode(preferences))
            preferences[AUTO] = next.automatic
            preferences[INTERVAL] = next.interval
            preferences[WIFI] = next.wifiOnly
            preferences[DAYS] = next.firstDays
            preferences[OVERLAP] = next.overlapHours
            preferences[METRICS] = next.enabled.map { it.name }.toSet()
            preferences[METRICS_ADDED] = true
            preferences[RETRY] = next.retryUntilMillis
        }
    }

    private fun decode(prefs: Preferences) = Settings(
        automatic = prefs[AUTO] ?: true,
        interval = prefs[INTERVAL] ?: 30,
        wifiOnly = prefs[WIFI] ?: false,
        firstDays = prefs[DAYS] ?: 7,
        overlapHours = prefs[OVERLAP] ?: 48,
        enabled = prefs[METRICS]?.let { names ->
            Metric.entries.filter { it.name in names || (prefs[METRICS_ADDED] != true && it.ordinal > Metric.SLEEP.ordinal) }.toSet()
        }
            ?: Metric.entries.toSet(),
        retryUntilMillis = prefs[RETRY] ?: 0,
    )

    private companion object {
        val AUTO = booleanPreferencesKey("auto")
        val INTERVAL = intPreferencesKey("interval")
        val WIFI = booleanPreferencesKey("wifi_only")
        val DAYS = intPreferencesKey("first_days")
        val OVERLAP = intPreferencesKey("overlap_hours")
        val METRICS = stringSetPreferencesKey("metrics")
        val METRICS_ADDED = booleanPreferencesKey("extended_metrics_added")
        val RETRY = longPreferencesKey("retry_until")
    }
}
