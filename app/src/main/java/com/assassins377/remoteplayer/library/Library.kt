package com.assassins377.remoteplayer.library

import android.content.Context
import com.assassins377.remoteplayer.data.RemoteFile
import com.assassins377.remoteplayer.data.RemoteFileRepository
import com.assassins377.remoteplayer.data.ServerConfig
import com.assassins377.remoteplayer.data.ServerStore
import com.assassins377.remoteplayer.meta.TitleParser
import com.assassins377.remoteplayer.settings.SettingsStore
import kotlinx.coroutines.cancelAndJoin
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
enum class LibraryMode { ALL, MOVIES, SERIES }

fun EntryRow.toRemoteFile() = RemoteFile(name, path, false, size, modified)
val EntryRow.key: String get() = "$serverId/$path"

object LibraryRows {
    fun of(serverId: String, path: String, name: String, size: Long, modified: Long, addedAt: Long, scanId: Long, id: Long = 0): EntryRow {
        val p = TitleParser.parse(name)
        val ep = Episodes.detect(path.trim('/'), name)
        return EntryRow(id, serverId, path.trim('/'), name, p.display, p.display.lowercase().replace('ё', 'е'), p.year, size, modified,
            addedAt, scanId, LibrarySql.normalize(name), LibrarySql.normalize(p.display + " " + (ep?.title ?: "")),
            ep?.let { Episodes.key(it.title, it.year) }, ep?.title, ep?.season, ep?.episode)
    }
}

/** Recursive BFS scan of one server; pure (repository injected) for tests. */
object LibraryScanner {
    fun parseRoots(s: String): List<String> =
        s.split('\n', ',', ';').map { it.trim().trim('/') }.filter { it.isNotEmpty() }.distinct().ifEmpty { listOf("") }

    /**
     * Calls [onFolder] after each listed folder and [onBatch] with every [batchSize] videos (and the rest at the end).
     * Missing roots are skipped (all missing → throws); network failures throw; inaccessible subfolders are skipped. Returns the number of videos found.
     */
    suspend fun scan(
        server: ServerConfig, repo: RemoteFileRepository, roots: List<String>, maxDepth: Int,
        includeHidden: Boolean = false, maxFiles: Int = 50_000, batchSize: Int = 500,
        onFolder: (folders: Int, files: Int) -> Unit = { _, _ -> },
        onBatch: suspend (List<RemoteFile>) -> Unit = {},
    ): Int = coroutineScope {
        var found = 0; var folders = 0; var missingRoots = 0; var lastRootError: Exception? = null
        val batch = ArrayList<RemoteFile>()
        val seen = HashSet<String>()
        val queue = ArrayDeque(roots.map { it to 0 })
        while (queue.isNotEmpty() && found < maxFiles) {
            ensureActive()
            val (dir, depth) = queue.removeFirst()
            if (!seen.add(dir)) continue
            val items = try { repo.list(server, dir) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val kind = com.assassins377.remoteplayer.data.SmbErrors.classify(e)
                if (dir in roots && depth == 0) {
                    // A configured root that doesn't exist on this server is skipped; others fail the scan.
                    if (kind == com.assassins377.remoteplayer.data.SmbErrorKind.NOT_FOUND) { missingRoots++; lastRootError = e; continue }
                    throw e
                }
                // Network-level failures abort so the previous index is kept; access/not-found subfolders are skipped.
                if (kind.retryable || kind == com.assassins377.remoteplayer.data.SmbErrorKind.AUTH) throw e
                continue
            }
            folders++
            for (f in items) {
                if ((f.hidden || f.name.startsWith('.')) && !includeHidden) continue
                if (f.isDirectory) { if (depth < maxDepth) queue.addLast(f.path.trim('/') to depth + 1) }
                else if (f.isVideo && found < maxFiles) { batch += f; found++ }
            }
            onFolder(folders, found)
            if (batch.size >= batchSize) { onBatch(batch.toList()); batch.clear() }
        }
        if (missingRoots > 0 && missingRoots == roots.distinct().size) throw lastRootError!!
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
        RecentSearches.init(ctx)
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
                    LibraryRows.of(srv.id, p, f.name, f.size, f.modified, k?.addedAt ?: now, scanId, k?.id ?: 0)
                })
            })
        dao.deleteStale(srv.id, scanId)
    }

    suspend fun clear() { job?.cancelAndJoin(); db.dao().clear(); prefs?.edit()?.remove("updatedAt")?.apply(); _state.value = LibraryState() }
}

data class EpisodeInfo(val title: String, val season: Int, val episode: Int?, val year: Int? = null)

/** Episode detection from file name, with folder fallbacks ("Show/Season 2/E05.mkv", "Show (2008)/S01E02.mkv"). */
object Episodes {
    private val SEASON_DIR = Regex("""^(?:season|сезон|s)\s*(\d{1,2})$|^(\d{1,2})\s*сезон$""", RegexOption.IGNORE_CASE)
    private val BARE_EP = Regex("""(?:^|[ ._\-\[])(?:e|ep|episode|серия)\s*(\d{1,3})(?:[ ._\-\]]|$)""", RegexOption.IGNORE_CASE)

    /** Grouping key; the year keeps same-named shows apart (Doctor Who 1963 vs 2005). */
    fun key(title: String, year: Int? = null) = baseKey(title) + (year?.let { " $it" } ?: "")
    private fun baseKey(title: String) = title.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun detect(path: String, name: String): EpisodeInfo? {
        val dirs = path.split('/').dropLast(1)
        val p = TitleParser.parse(name)
        val seasonDir = dirs.lastOrNull()?.let { d -> SEASON_DIR.find(d.trim())?.let { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).toInt() } }
        var season = p.season ?: seasonDir
        var episode = p.episode
        if (episode == null && season != null && p.season == null) episode = BARE_EP.find(name)?.groupValues?.get(1)?.toInt()
        if (season == null) return null
        // Title: from the file name if it has one; otherwise from the show folder.
        val showDir = (if (seasonDir != null) dirs.dropLast(1) else dirs).lastOrNull()
        val fromFile = p.title.takeIf { it.isNotBlank() && !Regex("""^(?:s\d+(?:e\d+)?|e\d+|ep\s*\d+|\d+|(?:серия|episode)\s*\d+|\d+\s*серия)$""", RegexOption.IGNORE_CASE).matches(it.trim()) && p.season != null }
        val title = fromFile ?: showDir?.let { TitleParser.parseFolder(it)?.title ?: TitleParser.parse(it).title } ?: p.title
        val year = showDir?.let { TitleParser.parse(it).year } ?: p.year
        return EpisodeInfo(title, season, episode, year)
    }

    /** Next episode after [current] in an ordered episode list (by season, episode, path). */
    fun next(ordered: List<EntryRow>, current: EntryRow): EntryRow? {
        val i = ordered.indexOfFirst { it.serverId == current.serverId && it.path == current.path }
        return if (i >= 0) ordered.getOrNull(i + 1) else null
    }
}

/** Last 10 library searches (SharedPreferences "library_meta"). */
object RecentSearches {
    private var prefs: android.content.SharedPreferences? = null
    private val _items = MutableStateFlow<List<String>>(emptyList())
    val items: StateFlow<List<String>> = _items.asStateFlow()
    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("library_meta", Context.MODE_PRIVATE)
        _items.value = prefs?.getString("recent", "")?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
    }
    fun add(q: String) {
        val t = q.trim(); if (t.length < 2) return
        _items.value = push(_items.value, t); prefs?.edit()?.putString("recent", _items.value.joinToString("\n"))?.apply()
    }
    fun clear() { _items.value = emptyList(); prefs?.edit()?.remove("recent")?.apply() }
    fun push(list: List<String>, q: String, max: Int = 10) = (listOf(q) + list.filterNot { it.equals(q, ignoreCase = true) }).take(max)
}
