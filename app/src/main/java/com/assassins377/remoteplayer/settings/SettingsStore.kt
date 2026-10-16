package com.assassins377.remoteplayer.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.assassins377.remoteplayer.data.SmbPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.settingsStore by preferencesDataStore(name = "settings")

/** DataStore-backed settings with a synchronous StateFlow cache (defaults until [init]). */
object SettingsStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    val current: AppSettings get() = _settings.value
    private var context: Context? = null

    fun init(ctx: Context) {
        if (context != null) return
        val c = ctx.applicationContext ?: ctx; context = c
        val flow = c.settingsStore.data.map { p -> AppSettings.fromMap(p.asMap().mapKeys { it.key.name }) }
        _settings.value = runBlocking { flow.first() }
        scope.launch {
            flow.collect { new ->
                val old = _settings.value
                _settings.value = new
                if (networkChanged(old, new)) { SmbPool.invalidateAll(); com.assassins377.remoteplayer.data.HttpClients.invalidateAll() }
            }
        }
    }

    fun networkChanged(a: AppSettings, b: AppSettings) =
        a.timeoutSec != b.timeoutSec || a.smbDialect != b.smbDialect || a.smbEncryption != b.smbEncryption

    /** Stores one setting by property name. Enums are stored by name. */
    fun set(key: String, value: Any) {
        val c = context ?: return
        scope.launch {
            c.settingsStore.edit { p ->
                when (value) {
                    is Boolean -> p[booleanPreferencesKey(key)] = value
                    is Int -> p[intPreferencesKey(key)] = value
                    is Long -> p[longPreferencesKey(key)] = value
                    is Float -> p[floatPreferencesKey(key)] = value
                    is Enum<*> -> p[stringPreferencesKey(key)] = value.name
                    else -> p[stringPreferencesKey(key)] = value.toString()
                }
            }
        }
    }

    @Suppress("unused") private fun Preferences.raw() = asMap()
}
