package com.assassins377.remoteplayer.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.StatFs
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.work.*
import com.assassins377.remoteplayer.R
import com.assassins377.remoteplayer.player.RoutingDataSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

@Serializable
data class DownloadItem(val uri: String, val name: String, val fileName: String, val addedAt: Long, val totalBytes: Long = -1, val done: Boolean = false)

/** Copies a remote file to [dest], resuming from the partial file's length. Pure-ish (data source injected). */
@OptIn(UnstableApi::class)
object FileCopier {
    suspend fun copy(source: DataSource, uri: String, dest: File, onProgress: suspend (Long, Long) -> Unit): Long = withContext(Dispatchers.IO) {
        val start = if (dest.exists()) dest.length() else 0L
        val len = source.open(DataSpec.Builder().setUri(android.net.Uri.parse(uri)).setPosition(start).build())
        val total = if (len == C.LENGTH_UNSET.toLong()) -1 else start + len
        try {
            RandomAccessFile(dest, "rw").use { raf ->
                raf.seek(start)
                val buf = ByteArray(256 * 1024); var done = start; var last = 0L
                while (true) {
                    ensureActive()
                    val n = source.read(buf, 0, buf.size)
                    if (n == C.RESULT_END_OF_INPUT) break
                    raf.write(buf, 0, n); done += n
                    if (done - last > 1_000_000) { last = done; onProgress(done, total) }
                }
                onProgress(done, total)
                done
            }
        } finally { runCatching { source.close() } }
    }
}

@OptIn(UnstableApi::class)
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val uri = inputData.getString("uri") ?: return Result.failure()
        val item = DownloadStore.get(uri) ?: return Result.failure()
        val dest = DownloadStore.fileOf(item) ?: return Result.failure()
        dest.parentFile?.mkdirs()
        runCatching { setForeground(foregroundInfo(item.name, 0)) }
        return try {
            val total = FileCopier.copy(RoutingDataSourceFactory(DefaultDataSource.Factory(applicationContext)).createDataSource(), uri, dest) { done, total ->
                setProgress(workDataOf("done" to done, "total" to total))
                if (total > 0) runCatching { setForeground(foregroundInfo(item.name, (done * 100 / total).toInt())) }
            }
            DownloadStore.markDone(uri, total)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf("error" to (e.message ?: "error")))
        }
    }

    private fun foregroundInfo(title: String, pct: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("downloads", applicationContext.getString(R.string.downloads), NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, "downloads").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title).setProgress(100, pct, pct == 0).setOngoing(true).setOnlyAlertOnce(true).build()
        val id = 4000 + (id.hashCode() and 0xfff)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(id, n)
    }
}

/** Download list (filesDir/downloads.json), files in getExternalFilesDir("downloads") (no storage permission needed). */
object DownloadStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()
    private var meta: File? = null
    private var dir: File? = null
    private var ctx: Context? = null

    fun init(c: Context, downloadsDir: File? = c.getExternalFilesDir("downloads") ?: File(c.filesDir, "downloads")) {
        ctx = c.applicationContext; dir = downloadsDir
        meta = File(c.filesDir, "downloads.json").also { f -> if (f.exists()) runCatching { _items.value = json.decodeFromString(f.readText()) } }
    }

    fun get(uri: String) = _items.value.firstOrNull { it.uri == uri }
    fun fileOf(i: DownloadItem): File? = dir?.let { File(it, i.fileName) }
    /** Local file for a finished download of [uri], else null (player uses it instead of the network). */
    fun localFile(uri: String): File? = get(uri)?.takeIf { it.done }?.let(::fileOf)?.takeIf { it.exists() }

    fun safeName(uri: String, name: String): String {
        val h = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
        return h + "_" + name.replace(Regex("""[\\/:*?"<>|]"""), "_")
    }

    fun enqueue(uri: String, name: String) {
        val c = ctx ?: return
        if (get(uri) == null) update { it + DownloadItem(uri, name, safeName(uri, name), System.currentTimeMillis()) }
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf("uri" to uri))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, java.time.Duration.ofSeconds(30))
            .addTag(TAG).addTag(tagFor(uri)).build()
        WorkManager.getInstance(c).enqueueUniqueWork(tagFor(uri), ExistingWorkPolicy.KEEP, req)
    }

    /** Collision-resistant unique work name (full SHA-256 of the URI). */
    fun tagFor(uri: String) = "dl:" + MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }
    const val TAG = "download"

    fun markDone(uri: String, total: Long) = update { l -> l.map { if (it.uri == uri) it.copy(done = true, totalBytes = total) else it } }

    fun remove(uri: String) {
        ctx?.let { WorkManager.getInstance(it).cancelUniqueWork(tagFor(uri)) }
        get(uri)?.let { fileOf(it)?.delete() }
        update { l -> l.filterNot { it.uri == uri } }
    }

    fun usedBytes(): Long = _items.value.sumOf { fileOf(it)?.length() ?: 0 }
    fun freeBytes(): Long = dir?.let { d -> runCatching { d.mkdirs(); StatFs(d.path).availableBytes }.getOrNull() } ?: 0

    private fun update(t: (List<DownloadItem>) -> List<DownloadItem>) {
        val n = synchronized(this) { t(_items.value).also { _items.value = it } }
        persist()
    }

    private val writer = kotlinx.coroutines.sync.Mutex()
    /** One writer at a time, always the latest snapshot, temp file + rename. */
    private fun persist() {
        val f = meta ?: return
        scope.launch { writer.withLock { runCatching { com.assassins377.remoteplayer.settings.AtomicFiles.write(f, json.encodeToString(_items.value)) } } }
    }
}
