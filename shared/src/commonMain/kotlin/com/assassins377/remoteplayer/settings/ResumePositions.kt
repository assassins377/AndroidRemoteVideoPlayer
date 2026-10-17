package com.assassins377.remoteplayer.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ResumeEntry(val positionMs: Long, val durationMs: Long = 0, val updatedAt: Long = 0) {
    /** 0..1, or null when duration unknown. */
    val progress: Float? get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else null
}

/**
 * Pure resume-position logic: per-file key -> position (+duration, time), LRU-capped.
 * Positions near the start/end are not stored (start from beginning next time).
 */
class ResumePositions(private val maxEntries: Int = 500, private val now: () -> Long = System::currentTimeMillis) {
    private val map = LinkedHashMap<String, ResumeEntry>(16, 0.75f, true)

    fun get(key: String): Long? = map[key]?.positionMs

    /** Returns true if stored state changed. */
    fun update(key: String, positionMs: Long, durationMs: Long): Boolean {
        val keep = positionMs >= MIN_MS && (durationMs <= 0 || durationMs - positionMs > END_MARGIN_MS)
        if (!keep) return map.remove(key) != null
        map[key] = ResumeEntry(positionMs, durationMs, now())
        while (map.size > maxEntries) map.remove(map.keys.first())
        return true
    }

    fun remove(key: String) = map.remove(key) != null

    /** Most recently watched first ("Continue watching"). */
    fun recent(limit: Int): List<Pair<String, ResumeEntry>> =
        map.entries.sortedByDescending { it.value.updatedAt }.take(limit).map { it.key to it.value }

    fun clear() = map.clear()
    fun snapshot(): Map<String, ResumeEntry> = LinkedHashMap(map)
    fun load(m: Map<String, ResumeEntry>) { map.clear(); map.putAll(m) }

    companion object {
        const val MIN_MS = 5_000L; const val END_MARGIN_MS = 10_000L
        private val json = Json { ignoreUnknownKeys = true }

        /** Reads current format, or the old `{key: positionMs}` format. */
        fun decode(raw: String): Map<String, ResumeEntry> =
            runCatching { json.decodeFromString<Map<String, ResumeEntry>>(raw) }.getOrElse {
                json.decodeFromString<Map<String, Long>>(raw).mapValues { ResumeEntry(it.value) }
            }
    }
}

