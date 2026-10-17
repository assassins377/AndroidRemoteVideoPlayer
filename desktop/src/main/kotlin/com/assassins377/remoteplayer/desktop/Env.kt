package com.assassins377.remoteplayer.desktop

import kotlinx.coroutines.launch
import com.assassins377.remoteplayer.core.CoreSettings
import com.assassins377.remoteplayer.core.CoreStrings
import com.assassins377.remoteplayer.core.FileKeyValue
import com.assassins377.remoteplayer.data.Repositories
import com.assassins377.remoteplayer.data.ServerConfig
import com.assassins377.remoteplayer.library.LibraryDatabase
import com.assassins377.remoteplayer.library.LibraryStore
import com.assassins377.remoteplayer.library.open
import com.assassins377.remoteplayer.settings.AppSettings
import com.assassins377.remoteplayer.settings.AtomicFiles
import com.assassins377.remoteplayer.settings.ResumePositions
import com.assassins377.remoteplayer.settings.WatchedStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File

/** XDG directories: config (~/.config/remoteplayer), data (~/.local/share/remoteplayer), cache (~/.cache/remoteplayer). */
class Dirs(val config: File, val data: File, val cache: File) {
    companion object {
        fun xdg(env: Map<String, String> = System.getenv(), home: String = System.getProperty("user.home")): Dirs {
            fun d(k: String, def: String) = File(env[k]?.takeIf { it.isNotBlank() } ?: "$home/$def", "remoteplayer")
            return Dirs(d("XDG_CONFIG_HOME", ".config"), d("XDG_DATA_HOME", ".local/share"), d("XDG_CACHE_HOME", ".cache"))
        }
    }
}

/** Settings persisted as JSON (subset of [AppSettings] keys); applied through [AppSettings.fromMap]. */
class DesktopSettings(private val file: File) {
    private val _s = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _s.asStateFlow()
    val current get() = _s.value

    private fun load(): AppSettings = runCatching {
        val o = Json.parseToJsonElement(file.readText()).jsonObject
        AppSettings.fromMap(o.mapValues { (_, v) -> v.jsonPrimitive.let { p -> p.booleanOrNull ?: p.longOrNull ?: p.doubleOrNull ?: p.content } })
    }.getOrDefault(AppSettings())

    fun update(f: (AppSettings) -> AppSettings) {
        val n = f(_s.value); _s.value = n
        val o = buildJsonObject {
            put("language", n.language.name); put("theme", n.theme.name); put("resume", n.resume)
            put("autoLoadSubtitles", n.autoLoadSubtitles); put("showHidden", n.showHidden); put("sort", n.sort.name)
            put("timeoutSec", n.timeoutSec); put("retryCount", n.retryCount); put("seekStepSec", n.seekStepSec)
            put("tmdbEnabled", n.tmdbEnabled); put("libraryRoots", n.libraryRoots); put("libraryMaxDepth", n.libraryMaxDepth)
            put("libraryRefreshOnStart", n.libraryRefreshOnStart); put("subtitleOffsetMs", n.subtitleOffsetMs)
        }
        file.parentFile.mkdirs(); AtomicFiles.write(file, o.toString())
    }
}

@Serializable private data class StoredServers(val servers: List<ServerConfig> = emptyList())

/** Servers in servers.json (no passwords); passwords in [Secrets] keyed by server id. */
class DesktopServers(private val file: File, private val secrets: Secrets) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _servers = MutableStateFlow(load())
    val servers: StateFlow<List<ServerConfig>> = _servers.asStateFlow()

    private fun load(): List<ServerConfig> = runCatching { json.decodeFromString<StoredServers>(file.readText()).servers }.getOrDefault(emptyList())
        .map { it.copy(password = secrets.get("server:" + it.id).orEmpty()) }

    fun get(id: String) = _servers.value.firstOrNull { it.id == id }

    fun save(s: ServerConfig) {
        secrets.put("server:" + s.id, s.password)
        _servers.value = _servers.value.filterNot { it.id == s.id } + s
        persist()
    }

    fun remove(id: String) { secrets.remove("server:$id"); _servers.value = _servers.value.filterNot { it.id == id }; persist() }

    private fun persist() { file.parentFile.mkdirs(); AtomicFiles.write(file, json.encodeToString(StoredServers(_servers.value))) }
}

/** Resume positions in resume.json (same logic as Android: LRU, ignores start/end). */
class DesktopResume(private val file: File) {
    private val positions = ResumePositions()
    init { runCatching { positions.load(ResumePositions.decode(file.readText())) } }
    @Synchronized fun get(key: String) = positions.get(key)
    @Synchronized fun save(key: String, pos: Long, dur: Long) {
        if (positions.update(key, pos, dur)) { file.parentFile.mkdirs(); AtomicFiles.write(file, Json.encodeToString(positions.snapshot())) }
    }
    @Synchronized fun recent(n: Int) = positions.recent(n)
}

/** Process-wide desktop services. */
object Env {
    lateinit var dirs: Dirs; private set
    lateinit var secrets: Secrets; private set
    lateinit var settings: DesktopSettings; private set
    lateinit var servers: DesktopServers; private set
    lateinit var resume: DesktopResume; private set
    lateinit var library: LibraryDatabase; private set
    lateinit var stream: StreamServer; private set
    lateinit var downloads: Downloads; private set

    fun init(d: Dirs = Dirs.xdg()) {
        dirs = d
        listOf(d.config, d.data, d.cache).forEach { it.mkdirs() }
        secrets = Secrets.best(d.config)
        settings = DesktopSettings(File(d.config, "settings.json"))
        servers = DesktopServers(File(d.config, "servers.json"), secrets)
        resume = DesktopResume(File(d.data, "resume.json"))
        CoreSettings.provider = { settings.current }
        CoreStrings.lookup = { key -> Strings.android(key) }
        downloads = Downloads.forServers({ servers }, d)
        WatchedStore.init(d.data)
        // Manual watched toggles on Jellyfin items are mirrored to the server.
        WatchedStore.onUserChange = { key, watched ->
            val id = key.substringBefore('/')
            servers.get(id)?.takeIf { it.type == com.assassins377.remoteplayer.data.ServerType.JELLYFIN }?.let { srv ->
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { com.assassins377.remoteplayer.data.JellyfinRepository().setPlayed(srv, key.substringAfter('/'), watched) }
            }
        }
        library = LibraryDatabase.open(File(d.data, "library.db"))
        LibraryStore.servers = { servers.servers.value }
        LibraryStore.repositoryFor = { Repositories.forType(it.type) }
        LibraryStore.init(library, FileKeyValue(File(d.data, "library_meta.properties")), d.data)
        stream = StreamServer().start()
        Tmdb.init(File(d.cache, "tmdb_cache.json"), File(d.cache, "posters"))
    }
}
