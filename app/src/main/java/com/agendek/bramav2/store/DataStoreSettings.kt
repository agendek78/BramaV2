package com.agendek.bramav2.store

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.agendek.bramav2.domain.LastKnownRule
import com.agendek.bramav2.domain.PresenceRecord
import com.agendek.bramav2.domain.PresenceStore
import com.agendek.bramav2.domain.RuleCacheStore
import com.agendek.bramav2.domain.SettingsStore
import com.agendek.bramav2.protocol.RuleSnapshot
import com.agendek.bramav2.protocol.hexToBytes
import com.agendek.bramav2.protocol.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Niesekretne ustawienia i stany (spec 4.1, 4.3) w jednym DataStore: [SettingsStore], [RuleCacheStore],
 * a [PresenceStore] jako [presence] (obie mają `load()` bez parametrów).
 */
class DataStoreSettings(private val store: DataStore<Preferences>) : SettingsStore, RuleCacheStore {

    override val autoCloseDefault: Flow<Boolean> = store.data.map { it[AUTO_CLOSE] ?: false }.distinctUntilChanged()
    override val gateName: Flow<String?> = store.data.map { it[GATE_NAME] }.distinctUntilChanged()
    override val presenceNotificationEnabled: Flow<Boolean> = store.data.map { it[PRESENCE_ENABLED] ?: true }.distinctUntilChanged()

    override suspend fun setAutoCloseDefault(enabled: Boolean) {
        store.edit { it[AUTO_CLOSE] = enabled }
    }

    override suspend fun setGateName(name: String?) {
        store.edit { if (name == null) it.remove(GATE_NAME) else it[GATE_NAME] = name }
    }

    override suspend fun setPresenceNotificationEnabled(enabled: Boolean) {
        store.edit { it[PRESENCE_ENABLED] = enabled }
    }

    override suspend fun load(): LastKnownRule? {
        val p = store.data.first()
        val hex = p[RULE_SNAPSHOT] ?: return null
        return runCatching { LastKnownRule(RuleSnapshot.decode(hex.hexToBytes()), p[RULE_RECEIVED_AT] ?: 0L) }.getOrNull()
    }

    override suspend fun save(rule: LastKnownRule) {
        store.edit {
            it[RULE_SNAPSHOT] = rule.snapshot.encode().toHex()
            it[RULE_RECEIVED_AT] = rule.receivedAtMillis
        }
    }

    override suspend fun clear() {
        store.edit {
            it.remove(RULE_SNAPSHOT)
            it.remove(RULE_RECEIVED_AT)
        }
    }

    val presence: PresenceStore = object : PresenceStore {
        override suspend fun load(): PresenceRecord {
            val p = store.data.first()
            return PresenceRecord(quiet = p[PRESENCE_QUIET] ?: false, ruleSuppressed = p[PRESENCE_RULE_SUPPRESSED] ?: false)
        }

        override suspend fun save(record: PresenceRecord) {
            store.edit {
                it[PRESENCE_QUIET] = record.quiet
                it[PRESENCE_RULE_SUPPRESSED] = record.ruleSuppressed
            }
        }
    }

    companion object {
        /**
         * DataStore w [file]. Uszkodzony plik zastępuje się pustym: wartości domyślne są bezpieczne (autozamykanie
         * wyłączone, reguła nieznana, brak wyciszenia), a wyjątek w `data` zatrzymałby każdy odczyt ustawień.
         */
        fun create(file: File, scope: CoroutineScope): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
        ) { file }

        private val AUTO_CLOSE = booleanPreferencesKey("auto_close_default")
        private val GATE_NAME = stringPreferencesKey("gate_name")
        private val PRESENCE_ENABLED = booleanPreferencesKey("presence_enabled")
        private val RULE_SNAPSHOT = stringPreferencesKey("rule_snapshot")
        private val RULE_RECEIVED_AT = longPreferencesKey("rule_received_at")
        private val PRESENCE_QUIET = booleanPreferencesKey("presence_quiet")
        private val PRESENCE_RULE_SUPPRESSED = booleanPreferencesKey("presence_rule_suppressed")
    }
}
