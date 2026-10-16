package com.assassins377.remoteplayer.settings

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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
        persist()
    }

    /** Applies many marks at once with a single write. */
    @Synchronized fun setAll(marks: Map<String, Boolean>) {
        val cur = _items.value
        val next = cur.toMutableSet().apply { marks.forEach { (k, w) -> if (w) add(k) else remove(k) } }.toSet()
        if (next == cur) return
        _items.value = next
        persist()
    }

    private val writer = kotlinx.coroutines.sync.Mutex()
    /** Serialized, atomic (temp + rename) write of the latest snapshot. */
    private fun persist() {
        val f = file ?: return
        scope.launch { writer.withLock { runCatching { AtomicFiles.write(f, Json.encodeToString(_items.value.toList())) } } }
    }
    fun toggle(key: String) = set(key, !isWatched(key)).also { onUserChange?.invoke(key, isWatched(key)) }

    /** Hook for server-side sync (Jellyfin); called for user/player changes, not for synced-in states. */
    @Volatile var onUserChange: ((key: String, watched: Boolean) -> Unit)? = null
    fun markByUser(key: String, watched: Boolean) { set(key, watched); onUserChange?.invoke(key, watched) }

    fun shouldMark(positionMs: Long, durationMs: Long) = durationMs > 0 && positionMs >= durationMs * THRESHOLD
}
