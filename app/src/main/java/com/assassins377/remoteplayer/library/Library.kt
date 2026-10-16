package com.assassins377.remoteplayer.library

import android.content.Context
import com.assassins377.remoteplayer.data.RemoteFile
import com.assassins377.remoteplayer.data.RemoteFileRepository
import com.assassins377.remoteplayer.data.ServerConfig
import com.assassins377.remoteplayer.data.ServerStore
import com.assassins377.remoteplayer.meta.TitleParser
import com.assassins377.remoteplayer.settings.SettingsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Legacy JSON cache format (v1 of the library, imported once into Room). */
@Serializable
data class LibraryEntry(
    val serverId: String,
    val path: String,
    val name: String,
    val size: Long = 0,
    val modified: Long = 0,
    val addedAt: Long = 0,
)

@Serializable
data class LibraryData(val entries: List<LibraryEntry> = emptyList(), val updatedAt: Long = 0, val errors: Map<String, String> = emptyMap())

enum class LibrarySort { TITLE, YEAR, ADDED }

fun EntryRow.toRemoteFile() = RemoteFile(name, path, false, size, modified)
val EntryRow.key: String get() = "$serverId/$path"

object LibraryRows {
    fun of(serverId: String, path: String, name: String, size: Long, modified: Long, addedAt: Long, scanId: Long, id: Long = 0): EntryRow {
        val p = TitleParser.parse(name)
        return EntryRow(id, serverId, path.trim('/'), name, p.display, p.display.lowercase().replace('ё', 'е'), p.year, size, modified,
            addedAt, scanId, LibrarySql.normalize(name), LibrarySql.normalize(p.display))
    }
}

/** Recursive BFS scan of one server; pure (repository injected) for tests. */
object LibraryScanner {
    fun parseRoots(s: String): List<String> =
        s.split('\n', ',', ';').map { it.trim().trim('/') }.filter { it.isNotEmpty() }.distinct().ifEmpty { listOf("") }

    /**
     * Calls [onFolder] after each listed folder and [onBatch] with every [batchSize] videos (and the rest at the end).
     * A failing root folder throws; failing subfolders are skipped. Returns the number of videos found.
     */
    suspend fun scan(
        server: ServerConfig, repo: RemoteFileRepository, roots: List<String>, maxDepth: Int,
        includeHidden: Boolean = false, maxFiles: Int = 50_000, batchSize: Int = 500,
        onFolder: (folders: Int, files: Int) -> Unit = { _, _ -> },
        onBatch: suspend (List<RemoteFile>) -> Unit = {},
    ): Int = coroutineScope {
        var found = 0; var folders = 0
        val batch = ArrayList<RemoteFile>()
        val seen = HashSet<String>()
        val queue = ArrayDeque(roots.map { it to 0 })
        while (queue.isNotEmpty() && found < maxFiles) {
            ensureActive()
            val (dir, depth) = queue.removeFirst()
            if (!seen.add(dir)) continue
            val items = try { repo.list(server, dir) } catch (e: CancellationException) { throw e } catch (e: Exception) { if (dir in roots) throw e else continue }
            folders++
            for (f in items) {
                if ((f.hidden || f.name.startsWith('.')) && !includeHidden) continue
                if (f.isDirectory) { if (depth < maxDepth) queue.addLast(f.path.trim('/') to depth + 1) }
                else if (f.isVideo && found < maxFiles) { batch += f; found++ }
            }
            onFolder(folders, found)
            if (batch.size >= batchSize) { onBatch(batch.toList()); batch.clear() }
        }
        if (batch.isNotEmpty()) onBatch(batch.toList())
        found
    }
}

data class ServerProgress(val name: String, val folders: Int = 0, val files: Int = 0, val done: Boolean = false, val error: String? = null)

data class LibraryState(
    val scanning: Boolean = false,
    val progress: Map<String, ServerProgress> = emptyMap(),
    val updatedAt: Long = 0,
    val errors: Map<String, String> = emptyMap(),
) {
    val serversDone: Int get() = progress.values.count { it.done }
    val filesFound: Int get() = progress.values.sumOf { it.files }
}

/** Library index in Room; background rescans with per-server progress, limited parallelism, timeout and cancel. */
object LibraryStore {
    const val PARALLEL = 3
    var serverTimeoutMs = 15 * 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()
    @Volatile private var _db: LibraryDatabase? = null
    val db: LibraryDatabase get() = _db ?: error("LibraryStore not initialised")
    private var prefs: android.content.SharedPreferences? = null
    private var job: Job? = null

    fun init(ctx: Context, database: LibraryDatabase = LibraryDatabase.open(ctx.applicationContext)) {
        _db = database
        prefs = ctx.getSharedPreferences("library_meta", Context.MODE_PRIVATE)
        _state.value = LibraryState(updatedAt = prefs?.getLong("updatedAt", 0) ?: 0)
        val legacy = File(ctx.filesDir, "library.json")
        scope.launch {
            if (legacy.exists()) runCatching { importLegacy(legacy.readText()) }.also { legacy.delete() }
            if (SettingsStore.current.libraryRefreshOnStart) refresh()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** One-time import of the v1 JSON cache. */
    suspend fun importLegacy(text: String): Int {
        val d = json.decodeFromString<LibraryData>(text)
        val rows = d.entries.map { LibraryRows.of(it.serverId, it.path, it.name, it.size, it.modified, it.addedAt, 0) }
        rows.chunked(1000).forEach { db.dao().upsert(it) }
        if (d.updatedAt > 0 && _state.value.updatedAt == 0L) setUpdated(d.updatedAt)
        return rows.size
    }

    private fun setUpdated(t: Long) { prefs?.edit()?.putLong("updatedAt", t)?.apply(); _state.update { it.copy(updatedAt = t) } }

    @Synchronized fun refresh(): Job {
        job?.takeIf { it.isActive }?.let { return it }
        return scope.launch { refreshNow() }.also { job = it }
    }

    @Synchronized fun cancel() { job?.cancel() }

    suspend fun refreshNow(
        servers: List<ServerConfig> = ServerStore.servers.value,
        repoFor: (ServerConfig) -> RemoteFileRepository = { ServerStore.repositoryFor(it.type) },
    ) {
        val s = SettingsStore.current
        val roots = LibraryScanner.parseRoots(s.libraryRoots)
        val dao = db.dao()
        _state.update { it.copy(scanning = true, progress = servers.associate { srv -> srv.id to ServerProgress(srv.name) }) }
        try {
            dao.deleteServersExcept(servers.map { it.id }.ifEmpty { listOf("") })
            val limiter = Semaphore(PARALLEL)
            val errors = coroutineScope {
                servers.map { srv ->
                    async {
                        limiter.withPermit {
                            val err = try { withTimeout(serverTimeoutMs) { scanServer(srv, repoFor(srv), roots, s.libraryMaxDepth, s.showHidden) }; null }
                            catch (e: TimeoutCancellationException) { "timeout" }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { e.message ?: e.javaClass.simpleName }
                            _state.update { st -> st.copy(progress = st.progress + (srv.id to (st.progress[srv.id] ?: ServerProgress(srv.name)).copy(done = true, error = err))) }
                            err?.let { srv.id to it }
                        }
                    }
                }.awaitAll().filterNotNull().toMap()
            }
            _state.update { it.copy(errors = errors) }
            setUpdated(System.currentTimeMillis())
        } finally {
            _state.update { it.copy(scanning = false) }
        }
    }

    /** Upserts batches as they are found; stale rows are deleted only after a complete scan. */
    private suspend fun scanServer(srv: ServerConfig, repo: RemoteFileRepository, roots: List<String>, depth: Int, hidden: Boolean) {
        val dao = db.dao()
        val scanId = System.nanoTime()
        val known = dao.known(srv.id).associateBy { it.path }
        val now = System.currentTimeMillis()
        LibraryScanner.scan(srv, repo, roots, depth, hidden,
            onFolder = { folders, files -> _state.update { st -> st.copy(progress = st.progress + (srv.id to ServerProgress(srv.name, folders, files))) } },
            onBatch = { batch ->
                dao.upsert(batch.distinctBy { it.path }.map { f ->
                    val p = f.path.trim('/'); val k = known[p]
                    LibraryRows.of(srv.id, p, f.name, f.size, f.modified, k?.addedAt ?: f.modified.takeIf { it > 0 } ?: now, scanId, k?.id ?: 0)
                })
            })
        dao.deleteStale(srv.id, scanId)
    }

    suspend fun clear() { db.dao().clear(); prefs?.edit()?.remove("updatedAt")?.apply(); _state.value = LibraryState() }
}
