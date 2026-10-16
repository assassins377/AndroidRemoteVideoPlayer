package com.assassins377.remoteplayer.settings

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Favorite(val serverId: String, val path: String, val name: String, val isDirectory: Boolean, val addedAt: Long = 0) {
    val key: String get() = key(serverId, path)
    companion object { fun key(serverId: String, path: String) = "$serverId/${path.trim('/')}" }
}

/** Pure favourites list: unique by server+path, newest first. */
class FavoriteList(items: List<Favorite> = emptyList()) {
    private val map = LinkedHashMap<String, Favorite>().apply { items.forEach { put(it.key, it) } }

    fun contains(serverId: String, path: String) = map.containsKey(Favorite.key(serverId, path))

    /** Adds if absent, removes if present. Returns true if now a favourite. */
    fun toggle(f: Favorite): Boolean = if (map.remove(f.key) != null) false else { map[f.key] = f; true }

    fun removeServer(serverId: String) { map.values.removeAll { it.serverId == serverId } }

    fun all(): List<Favorite> = map.values.sortedWith(compareByDescending<Favorite> { it.addedAt }.thenBy { it.name.lowercase() })

    fun toJson(): String = json.encodeToString(map.values.toList())

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun fromJson(s: String) = FavoriteList(runCatching { json.decodeFromString<List<Favorite>>(s) }.getOrDefault(emptyList()))
    }
}

/** Favourites persisted to filesDir/favorites.json. */
object FavoritesStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var list = FavoriteList()
    private var file: File? = null
    private val _items = MutableStateFlow<List<Favorite>>(emptyList())
    val items: StateFlow<List<Favorite>> = _items.asStateFlow()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "favorites.json").also { if (it.exists()) list = FavoriteList.fromJson(it.readText()) }
        _items.value = list.all()
    }

    @Synchronized fun toggle(f: Favorite): Boolean = list.toggle(f.copy(addedAt = System.currentTimeMillis())).also { changed() }
    @Synchronized fun removeServer(serverId: String) { list.removeServer(serverId); changed() }

    private fun changed() {
        _items.value = list.all()
        val f = file ?: return; val json = list.toJson()
        scope.launch { runCatching { f.writeText(json) } }
    }
}

/** Instant search in the current folder: matches file name or cleaned title, case-insensitive, all words. */
object FileFilter {
    fun matches(name: String, cleanTitle: String?, query: String): Boolean {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val hay = (name.replace('.', ' ').replace('_', ' ') + " " + cleanTitle.orEmpty()).lowercase().replace('ё', 'е')
        return words.all { hay.contains(it.replace('ё', 'е')) }
    }
}
