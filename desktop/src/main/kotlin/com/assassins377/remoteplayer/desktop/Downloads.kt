package com.assassins377.remoteplayer.desktop

import com.assassins377.remoteplayer.data.RangeSource
import com.assassins377.remoteplayer.data.RangeSources
import com.assassins377.remoteplayer.data.ServerConfig
import com.assassins377.remoteplayer.settings.AtomicFiles
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@Serializable
enum class DownloadStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

@Serializable
data class DownloadItem(
    val id: String, val serverId: String, val path: String, val name: String, val target: String,
    val bytes: Long = 0, val total: Long = -1, val status: DownloadStatus = DownloadStatus.QUEUED, val error: String? = null,
) {
    val progress: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
}

/**
 * Offline copies: a sequential queue persisted to downloads.json. Partial files (*.part) resume with a byte range;
 * finished files are played locally by the player.
 */
class Downloads(
    private val stateFile: File,
    private val folderFile: File,
    private val defaultFolder: File,
    private val source: (serverId: String, path: String) -> RangeSource?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var running: Job? = null
    private var current: String? = null

    var folder: File
        get() = folderFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let(::File) ?: defaultFolder
        set(v) { folderFile.parentFile.mkdirs(); folderFile.writeText(v.absolutePath) }

    init { scope.launch { worker() }; wake.trySend(Unit) }

    private fun load(): List<DownloadItem> = runCatching { json.decodeFromString<List<DownloadItem>>(stateFile.readText()) }.getOrDefault(emptyList())
        .map { if (it.status == DownloadStatus.RUNNING) it.copy(status = DownloadStatus.QUEUED) else it }

    @Synchronized private fun update(id: String, f: (DownloadItem) -> DownloadItem) {
        _items.value = _items.value.map { if (it.id == id) f(it) else it }; persist()
    }
    private fun persist() { stateFile.parentFile.mkdirs(); AtomicFiles.write(stateFile, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(DownloadItem.serializer()), _items.value)) }

    @Synchronized fun enqueue(server: ServerConfig, path: String, name: String): DownloadItem {
        _items.value.firstOrNull { it.serverId == server.id && it.path == path && it.status != DownloadStatus.CANCELLED && it.status != DownloadStatus.FAILED }?.let { return it }
        val dir = File(folder, server.name.replace(Regex("""[/\\:]"""), "_")).apply { mkdirs() }
        var target = File(dir, name.replace('/', '_'))
        var n = 1
        while (target.exists() || _items.value.any { it.target == target.absolutePath }) target = File(dir, name.substringBeforeLast('.') + " (${n++})." + name.substringAfterLast('.', "mkv"))
        val item = DownloadItem(UUID.randomUUID().toString(), server.id, path, name, target.absolutePath)
        synchronized(this) { _items.value = _items.value + item; persist() }
        wake.trySend(Unit); return item
    }

    fun cancel(id: String) { update(id) { if (it.status == DownloadStatus.DONE) it else it.copy(status = DownloadStatus.CANCELLED) }; if (current == id) running?.cancel() }
    fun retry(id: String) { update(id) { it.copy(status = DownloadStatus.QUEUED, error = null) }; wake.trySend(Unit) }
    @Synchronized fun remove(id: String, deleteFile: Boolean) {
        cancel(id)
        _items.value.firstOrNull { it.id == id }?.let { if (deleteFile) { File(it.target).delete(); File(it.target + ".part").delete() } }
        _items.value = _items.value.filter { it.id != id }; persist()
    }

    /** Finished local copy of a remote file, if any. */
    fun localFile(serverId: String, path: String): File? = _items.value.firstOrNull {
        it.serverId == serverId && it.path.trim('/') == path.trim('/') && it.status == DownloadStatus.DONE
    }?.let { File(it.target) }?.takeIf { it.isFile }

    private suspend fun worker() {
        for (u in wake) while (true) {
            val next = _items.value.firstOrNull { it.status == DownloadStatus.QUEUED } ?: break
            current = next.id
            running = scope.launch { download(next) }
            running?.join(); current = null
        }
    }

    private suspend fun download(item: DownloadItem) {
        val part = File(item.target + ".part")
        try {
            update(item.id) { it.copy(status = DownloadStatus.RUNNING, error = null) }
            val src = source(item.serverId, item.path) ?: error("server removed")
            src.use { s ->
                val from = if (part.exists()) part.length() else 0L
                update(item.id) { it.copy(total = s.size, bytes = from) }
                s.open(from).use { input ->
                    FileOutputStream(part, from > 0).use { out ->
                        val buf = ByteArray(256 * 1024); var done = from; var last = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val r = input.read(buf); if (r < 0) break
                            out.write(buf, 0, r); done += r
                            val now = System.currentTimeMillis()
                            if (now - last > 300) { last = now; val d = done; update(item.id) { it.copy(bytes = d) } }
                        }
                        update(item.id) { it.copy(bytes = done) }
                    }
                }
                if (s.size > 0 && part.length() != s.size) error("incomplete: ${part.length()} of ${s.size}")
            }
            File(item.target).delete(); check(part.renameTo(File(item.target))) { "rename failed" }
            update(item.id) { it.copy(status = DownloadStatus.DONE) }
        } catch (e: CancellationException) {
            if (_items.value.firstOrNull { it.id == item.id }?.status == DownloadStatus.CANCELLED) part.delete()
        } catch (e: Exception) {
            update(item.id) { it.copy(status = DownloadStatus.FAILED, error = e.message ?: e.javaClass.simpleName) }
        }
    }

    /** Waits until the queue has no queued/running items (tests). */
    suspend fun awaitIdle() { while (_items.value.any { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING }) delay(50) }

    companion object {
        fun defaultFolder(env: Map<String, String> = System.getenv(), home: String = System.getProperty("user.home"), windows: Boolean = Os.isWindows): File =
            if (windows) File(File(env["USERPROFILE"]?.takeIf { it.isNotBlank() } ?: home, "Videos"), "Remote Video Player")
            else File(env["XDG_VIDEOS_DIR"]?.takeIf { it.isNotBlank() } ?: "$home/Videos", "Remote Video Player")

        fun forServers(servers: () -> DesktopServers, d: Dirs) = Downloads(
            File(d.data, "downloads.json"), File(d.config, "download-folder"), defaultFolder(),
            { id, path -> servers().get(id)?.let { RangeSources.open(it, path) } },
        )
    }
}
