package com.assassins377.remoteplayer.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest

/**
 * Random-access [MediaDataSource] over a Media3 [DataSource] (SMB / HTTP range / WebDAV). Sequential reads reuse the
 * open stream; a jump reopens it at the new offset. Counts bytes so a runaway extraction can be stopped.
 */
@OptIn(UnstableApi::class)
class StreamingMediaDataSource(private val factory: () -> DataSource, private val uri: android.net.Uri, private val maxBytes: Long = 64L * 1024 * 1024) : MediaDataSource() {
    private var ds: DataSource? = null
    private var pos = -1L
    private var size = -1L
    var bytesRead = 0L; private set

    private fun openAt(p: Long) {
        runCatching { ds?.close() }
        val d = factory()
        val len = d.open(DataSpec.Builder().setUri(uri).setPosition(p).build())
        if (size < 0 && len != C.LENGTH_UNSET.toLong()) size = p + len
        ds = d; pos = p
    }

    @Synchronized override fun getSize(): Long { if (size < 0) openAt(0); return size }

    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (size in 0..position) return -1
        if (bytesRead > maxBytes) throw java.io.IOException("thumbnail read limit")
        if (ds == null || position != pos) openAt(position)
        var total = 0
        while (total < length) {
            val n = ds!!.read(buffer, offset + total, length - total)
            if (n == C.RESULT_END_OF_INPUT) break
            total += n
        }
        pos += total; bytesRead += total
        return if (total == 0) -1 else total
    }

    @Synchronized override fun close() { runCatching { ds?.close() }; ds = null }
}

/** Frame at ~10 % of the video, scaled to 320 px wide JPEG in cacheDir/thumbs; one extraction at a time, cached (incl. failures). */
@OptIn(UnstableApi::class)
object Thumbnails {
    private val limiter = Semaphore(1)
    private val failed = HashSet<String>()
    private val inFlight = HashMap<String, CompletableDeferred<File?>>()
    private var dir: File? = null
    private var appCtx: Context? = null

    fun init(ctx: Context) { appCtx = ctx.applicationContext; dir = File(ctx.cacheDir, "thumbs").apply { mkdirs() } }

    fun fileFor(uri: String): File? = dir?.let { File(it, sha1(uri) + ".jpg") }
    fun cached(uri: String): File? = fileFor(uri)?.takeIf { it.exists() && it.length() > 0 }

    suspend fun get(uri: String): File? {
        cached(uri)?.let { return it }
        val ctx = appCtx ?: return null
        synchronized(failed) { if (uri in failed) return null }
        val (d, owner) = synchronized(inFlight) { inFlight[uri]?.let { it to false } ?: CompletableDeferred<File?>().also { inFlight[uri] = it }.let { it to true } }
        if (!owner) return d.await()
        val res = try {
            limiter.withPermit { withContext(Dispatchers.IO) { withTimeoutOrNull(45_000) { runCatching { extract(ctx, uri) }.getOrNull() } } }
        } finally { synchronized(inFlight) { inFlight.remove(uri) } }
        if (res == null) synchronized(failed) { failed += uri }
        d.complete(res)
        return res
    }

    private fun extract(ctx: Context, uri: String): File? {
        val out = fileFor(uri) ?: return null
        val src = StreamingMediaDataSource({ RoutingDataSourceFactory(DefaultDataSource.Factory(ctx)).createDataSource() }, android.net.Uri.parse(uri))
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(src)
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val atUs = PlayerFrame.positionUs(durMs)
            val bmp = (if (android.os.Build.VERSION.SDK_INT >= 27) mmr.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)
                else mmr.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)) ?: return null
            val scaled = if (bmp.width > 320) Bitmap.createScaledBitmap(bmp, 320, bmp.height * 320 / bmp.width, true) else bmp
            out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            return out
        } finally { runCatching { mmr.release() }; src.close() }
    }

    fun clear() { dir?.listFiles()?.forEach { it.delete() }; synchronized(failed) { failed.clear() } }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

object PlayerFrame {
    /** 10 % into the video (min 5 s if long enough), in µs; 0 when unknown. */
    fun positionUs(durationMs: Long): Long = if (durationMs <= 0) 0 else (maxOf(durationMs / 10, minOf(5_000, durationMs / 2))) * 1000
}
