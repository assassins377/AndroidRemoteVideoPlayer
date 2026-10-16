package com.assassins377.remoteplayer.settings

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** "Watched" marks keyed by "serverId/path", persisted to filesDir/watched.json. */
object WatchedStore {
    /** Played this far (fraction of duration) = watched. */
    const val THRESHOLD = 0.9f
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _items = MutableStateFlow<Set<String>>(emptySet())
    val items: StateFlow<Set<String>> = _items.asStateFlow()
    private var file: File? = null

    fun key(serverId: String, path: String) = "$serverId/${path.trim('/')}"

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "watched.json").also { f ->
            if (f.exists()) runCatching { _items.value = Json.decodeFromString<List<String>>(f.readText()).toSet() }
        }
    }

    fun isWatched(key: String) = key in _items.value
    @Synchronized fun set(key: String, watched: Boolean) {
        val cur = _items.value
        val next = if (watched) cur + key else cur - key
        if (next == cur) return
        _items.value = next
        val f = file ?: return; val json = Json.encodeToString(next.toList())
        scope.launch { runCatching { f.writeText(json) } }
    }
    fun toggle(key: String) = set(key, !isWatched(key))

    fun shouldMark(positionMs: Long, durationMs: Long) = durationMs > 0 && positionMs >= durationMs * THRESHOLD
}
