package com.assassins377.remoteplayer.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Pure resume-position logic: per-file key -> position ms, LRU-capped.
 * Positions near the start/end are not stored (start from beginning next time).
 */
class ResumePositions(private val maxEntries: Int = 500) {
    private val map = LinkedHashMap<String, Long>(16, 0.75f, true)

    fun get(key: String): Long? = map[key]

    /** Returns true if stored state changed. */
    fun update(key: String, positionMs: Long, durationMs: Long): Boolean {
        val keep = positionMs >= MIN_MS && (durationMs <= 0 || durationMs - positionMs > END_MARGIN_MS)
        if (!keep) return map.remove(key) != null
        map[key] = positionMs
        while (map.size > maxEntries) map.remove(map.keys.first())
        return true
    }

    fun clear() = map.clear()
    fun snapshot(): Map<String, Long> = LinkedHashMap(map)
    fun load(m: Map<String, Long>) { map.clear(); map.putAll(m) }

    companion object { const val MIN_MS = 5_000L; const val END_MARGIN_MS = 10_000L }
}

private val Context.historyStore by preferencesDataStore(name = "history")
private val KEY = stringPreferencesKey("resume_json")

/** Watch history persisted as JSON in DataStore. */
object ResumeStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val positions = ResumePositions()
    private var context: Context? = null

    fun init(ctx: Context) {
        context = ctx.applicationContext
        runCatching {
            val raw = runBlocking { context!!.historyStore.data.first()[KEY] }
            if (raw != null) positions.load(Json.decodeFromString<Map<String, Long>>(raw))
        }
    }

    @Synchronized fun get(key: String) = positions.get(key)

    @Synchronized fun save(key: String, positionMs: Long, durationMs: Long) {
        if (positions.update(key, positionMs, durationMs)) persist()
    }

    @Synchronized fun clear() { positions.clear(); persist() }

    private fun persist() {
        val c = context ?: return
        val json = Json.encodeToString(positions.snapshot())
        scope.launch { c.historyStore.edit { it[KEY] = json } }
    }
}
