package com.assassins377.remoteplayer.meta

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** User's manual choice for one file/folder: a specific TMDB entry, or "show without TMDB" ([meta] = null). */
@Serializable
data class TmdbOverride(val meta: TmdbMeta? = null, val tv: Boolean = false) {
    val hidden: Boolean get() = meta == null
}

/**
 * Manual overrides keyed by "serverId/path". Stored in their own file (tmdb_overrides.json),
 * so "clear cache" (automatic results) does not remove them.
 */
class OverrideStore {
    private val map = HashMap<String, TmdbOverride>()

    @Synchronized fun get(key: String): TmdbOverride? = map[key]

    /** null = reset to automatic. */
    @Synchronized fun set(key: String, o: TmdbOverride?) { if (o == null) map.remove(key) else map[key] = o }

    @Synchronized fun size() = map.size
    @Synchronized fun toJson(): String = json.encodeToString(map.toMap())
    @Synchronized fun loadJson(s: String) { map.clear(); map.putAll(json.decodeFromString<Map<String, TmdbOverride>>(s)) }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun key(serverId: String, path: String) = "$serverId/${path.trim('/')}"
    }
}
