package com.assassins377.remoteplayer.data

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import okhttp3.Request
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/** Byte-range access to a remote file (for local streaming proxies, e.g. the desktop player). Blocking. */
interface RangeSource : Closeable {
    /** Total size in bytes, -1 if unknown. */
    val size: Long
    /** Stream from byte [from] to the end. */
    fun open(from: Long): InputStream
}

object RangeSources {
    fun open(server: ServerConfig, path: String): RangeSource = when (server.type) {
        ServerType.SMB -> SmbRange(server, path.trim('/'))
        ServerType.JELLYFIN -> HttpRange(server.copy(username = "", password = ""), JellyfinRepository().resolveStream(server, path))
        else -> HttpRange(server, if (path.isEmpty()) server.host else UrlPaths.join(server.host, path))
    }
}

private class SmbRange(private val server: ServerConfig, path: String) : RangeSource {
    private val file = SmbPool.withRetry(server) {
        SmbPool.share(server).openFile(path.replace('/', '\\'), setOf(AccessMask.GENERIC_READ), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
    }
    override val size: Long = file.fileInformation.standardInformation.endOfFile
    override fun open(from: Long): InputStream = object : InputStream() {
        private var pos = from
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= size) return -1
            val n = SmbPool.withRetry(server) { file.read(b, pos, off, minOf(len, 1 shl 20)) }
            if (n <= 0) return -1
            pos += n; return n
        }
    }
    override fun close() { runCatching { file.close() } }
}

private class HttpRange(server: ServerConfig, private val url: String) : RangeSource {
    private val client = HttpClients.forServer(server)
    override val size: Long = runCatching {
        client.newCall(Request.Builder().url(url).head().build()).execute().use { r ->
            HttpErrors.check(r); r.header("Content-Length")?.toLongOrNull() ?: -1L
        }
    }.getOrElse { if (it is SmbError) throw it else -1L }
    override fun open(from: Long): InputStream {
        val r = client.newCall(Request.Builder().url(url).header("Range", "bytes=$from-").build()).execute()
        HttpErrors.check(r)
        val body = r.body ?: throw IOException("empty body")
        val stream = body.byteStream()
        // Server ignored Range: skip manually.
        if (from > 0 && r.code == 200) { var left = from; while (left > 0) { val s = stream.skip(left); if (s <= 0) break; left -= s } }
        return stream
    }
    override fun close() {}
}
