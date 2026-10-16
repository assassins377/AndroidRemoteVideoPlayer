package com.assassins377.remoteplayer.library

import android.content.Context
import com.assassins377.remoteplayer.data.RemoteFile
import com.assassins377.remoteplayer.data.RemoteFileRepository
import com.assassins377.remoteplayer.data.ServerConfig
import com.assassins377.remoteplayer.data.ServerStore
import com.assassins377.remoteplayer.meta.TitleParser
import com.assassins377.remoteplayer.settings.FileFilter
import com.assassins377.remoteplayer.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** One indexed video file. */
@Serializable
data class LibraryEntry(
    val serverId: String,
    val path: String,
    val name: String,
    val size: Long = 0,
    val modified: Long = 0,
    /** When this file first appeared in the index (epoch ms). */
    val addedAt: Long = 0,
) {
    val key: String get() = "$serverId/$path"
    val year: Int? get() = TitleParser.parse(name).year
    val title: String get() = TitleParser.parse(name).display
    fun toRemoteFile() = RemoteFile(name, path, false, size, modified)
}

@Serializable
data class LibraryData(
    val entries: List<LibraryEntry> = emptyList(),
    val updatedAt: Long = 0,
    /** serverId -> last error message of that server's scan. */
    val errors: Map<String, String> = emptyMap(),
)

enum class LibrarySort { TITLE, YEAR, ADDED }

/** Recursive scan of one server; pure (repository injected) for tests. */
object LibraryScanner {
    /** Parses the "root folders" setting: one path per line or comma-separated; empty = share/base root. */
    fun parseRoots(s: String): List<String> =
        s.split('\n', ',', ';').map { it.trim().trim('/') }.filter { it.isNotEmpty() }.distinct().ifEmpty { listOf("") }

    suspend fun scan(
        server: ServerConfig,
        repo: RemoteFileRepository,
        roots: List<String>,
        maxDepth: Int,
        includeHidden: Boolean = false,
        maxFiles: Int = 50_000,
    ): List<RemoteFile> = coroutineScope {
        val out = ArrayList<RemoteFile>()
        val seen = HashSet<String>()
        // BFS; depth 0 = root folder itself.
        val queue = ArrayDeque(roots.map { it to 0 })
        while (queue.isNotEmpty() && out.size < maxFiles) {
            ensureActive()
            val (dir, depth) = queue.removeFirst()
            if (!seen.add(dir)) continue
            val items = try { repo.list(server, dir) } catch (e: Exception) { if (dir in roots) throw e else continue }
            for (f in items) {
                if (f.hidden && !includeHidden) continue
                if (f.name.startsWith('.') && !includeHidden) continue
                if (f.isDirectory) { if (depth < maxDepth) queue.addLast(f.path.trim('/') to depth + 1) }
                else if (f.isVideo) out += f
            }
        }
        out
    }

    /** Builds entries for [serverId], keeping the original addedAt of files already known. */
    fun merge(serverId: String, files: List<RemoteFile>, previous: List<LibraryEntry>, now: Long): List<LibraryEntry> {
        val old = previous.filter { it.serverId == serverId }.associateBy { it.path }
        return files.distinctBy { it.path }.map { f ->
            LibraryEntry(serverId, f.path.trim('/'), f.name, f.size, f.modified, old[f.path.trim('/')]?.addedAt ?: f.modified.takeIf { it > 0 } ?: now)
        }
    }
}

/** Search / filter / sort over the index; pure for tests. */
object LibraryQuery {
    fun apply(entries: List<LibraryEntry>, query: String, servers: Set<String>?, sort: LibrarySort, liveServers: Set<String>): List<LibraryEntry> {
        val parsed = entries.asSequence()
            .filter { it.serverId in liveServers && (servers == null || it.serverId in servers) }
            .map { it to TitleParser.parse(it.name) }
            .filter { (e, p) -> FileFilter.matches(e.name, p.display, query) }
            .toList()
        val byTitle = compareBy<Pair<LibraryEntry, com.assassins377.remoteplayer.meta.ParsedTitle>> { it.second.display.lowercase() }
        val sorted = when (sort) {
            LibrarySort.TITLE -> parsed.sortedWith(byTitle)
            LibrarySort.YEAR -> parsed.sortedWith(compareByDescending<Pair<LibraryEntry, com.assassins377.remoteplayer.meta.ParsedTitle>> { it.second.year ?: 0 }.then(byTitle))
            LibrarySort.ADDED -> parsed.sortedWith(compareByDescending<Pair<LibraryEntry, com.assassins377.remoteplayer.meta.ParsedTitle>> { it.first.addedAt }.then(byTitle))
        }
        return sorted.map { it.first }
    }
}

data class LibraryState(val data: LibraryData = LibraryData(), val scanning: Boolean = false, val progress: String = "")

/** Index persisted to filesDir/library.json; refreshed in the background. */
object LibraryStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()
    private var file: File? = null
    private var job: Job? = null

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "library.json").also { f ->
            if (f.exists()) runCatching { _state.value = LibraryState(json.decodeFromString<LibraryData>(f.readText())) }
        }
        if (SettingsStore.current.libraryRefreshOnStart) refresh()
    }

    fun decode(s: String): LibraryData = json.decodeFromString(s)
    fun encode(d: LibraryData): String = json.encodeToString(d)

    /** Starts a background rescan of all servers (no-op if one is running). */
    @Synchronized fun refresh(): Job {
        job?.takeIf { it.isActive }?.let { return it }
        return scope.launch { refreshNow() }.also { job = it }
    }

    suspend fun refreshNow(servers: List<ServerConfig> = ServerStore.servers.value, repoFor: (ServerConfig) -> RemoteFileRepository = { ServerStore.repositoryFor(it.type) }) {
        val s = SettingsStore.current
        val roots = LibraryScanner.parseRoots(s.libraryRoots)
        _state.value = _state.value.copy(scanning = true, progress = "")
        try {
            val prev = _state.value.data
            val results = coroutineScope {
                servers.map { srv ->
                    async {
                        srv.id to runCatching { LibraryScanner.scan(srv, repoFor(srv), roots, s.libraryMaxDepth, s.showHidden) }
                            .also { _state.value = _state.value.copy(progress = srv.name) }
                    }
                }.awaitAll()
            }
            val now = System.currentTimeMillis()
            val entries = ArrayList<LibraryEntry>(); val errors = HashMap<String, String>()
            for ((id, r) in results) {
                r.onSuccess { entries += LibraryScanner.merge(id, it, prev.entries, now) }
                    .onFailure { e -> errors[id] = e.message ?: e.javaClass.simpleName; entries += prev.entries.filter { it.serverId == id } } // keep old on error
            }
            val data = LibraryData(entries, now, errors)
            _state.value = LibraryState(data)
            file?.let { f -> runCatching { f.writeText(encode(data)) } }
        } finally {
            if (_state.value.scanning) _state.value = _state.value.copy(scanning = false)
        }
    }

    fun clear() { _state.value = LibraryState(); file?.delete() }
}
