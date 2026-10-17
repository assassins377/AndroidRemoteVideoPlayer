package com.assassins377.remoteplayer.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.historyStore by preferencesDataStore(name = "history")
private val KEY = stringPreferencesKey("resume_json")

/** Watch history persisted as JSON in DataStore. */
object ResumeStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val positions = ResumePositions()
    private var context: Context? = null
    private val _version = MutableStateFlow(0)
    /** Bumped on every change, for UI refresh. */
    val version: StateFlow<Int> = _version

    fun init(ctx: Context) {
        context = ctx.applicationContext
        runCatching {
            val raw = runBlocking { context!!.historyStore.data.first()[KEY] }
            if (raw != null) positions.load(ResumePositions.decode(raw))
        }
    }

    @Synchronized fun get(key: String) = positions.get(key)
    @Synchronized fun recent(limit: Int = 10) = positions.recent(limit)

    @Synchronized fun save(key: String, positionMs: Long, durationMs: Long) {
        if (positions.update(key, positionMs, durationMs)) persist()
    }

    @Synchronized fun remove(key: String) { if (positions.remove(key)) persist() }

    @Synchronized fun clear() { positions.clear(); persist() }

    private fun persist() {
        _version.value++
        val c = context ?: return
        runCatching { com.assassins377.remoteplayer.ContinueWidget.update(c) }
        val json = Json.encodeToString(positions.snapshot())
        scope.launch { c.historyStore.edit { it[KEY] = json } }
    }
}
