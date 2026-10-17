package com.assassins377.remoteplayer.desktop

import com.assassins377.remoteplayer.data.RangeSources
import com.assassins377.remoteplayer.data.ServerConfig
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Loopback HTTP server with Range support: VLC plays http://127.0.0.1:port/<token>/name while bytes come from
 * SMB/WebDAV/HTTP/Jellyfin through the shared clients (auth, pinned certs, retries). Tokens are random; loopback only.
 */
class StreamServer(bind: InetAddress? = InetAddress.getLoopbackAddress()) {
    /** bind = null listens on all interfaces (casting: the renderer fetches from the LAN). */
    private val socket = ServerSocket(0, 50, bind)
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "stream").apply { isDaemon = true } }
    private val items = ConcurrentHashMap<String, Pair<ServerConfig, String>>()
    val port: Int get() = socket.localPort

    fun start(): StreamServer { Thread({ while (!socket.isClosed) runCatching { val s = socket.accept(); pool.execute { handle(s) } } }, "stream-accept").apply { isDaemon = true }.start(); return this }

    fun register(server: ServerConfig, path: String, host: String = "127.0.0.1"): String {
        val token = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        items[token] = server to path
        val name = java.net.URLEncoder.encode(path.substringAfterLast('/'), "UTF-8").replace("+", "%20")
        return "http://$host:$port/$token/$name"
    }

    private fun handle(s: Socket) = s.use { sock ->
        val inp = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val req = inp.readLine() ?: return
        val headers = generateSequence { inp.readLine()?.takeIf { it.isNotEmpty() } }.associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val parts = req.split(' ')
        val out = sock.getOutputStream()
        val token = parts.getOrNull(1)?.trim('/')?.substringBefore('/')
        val item = token?.let { items[it] } ?: return respond(out, "404 Not Found", emptyMap())
        val head = parts[0] == "HEAD"
        val src = runCatching { RangeSources.open(item.first, item.second) }.getOrElse { return respond(out, "502 Bad Gateway", emptyMap()) }
        try {
            val size = src.size
            val range = Range.parse(headers["range"])
            val start = range?.first ?: 0L
            if (size >= 0 && start >= size && size > 0) return respond(out, "416 Range Not Satisfiable", mapOf("Content-Range" to "bytes */$size"))
            val end = range?.second?.let { if (size >= 0) minOf(it, size - 1) else it } ?: (size - 1)
            val len = if (size >= 0) end - start + 1 else -1
            val h = linkedMapOf("Accept-Ranges" to "bytes", "Content-Type" to mime(item.second), "Connection" to "close")
            // DLNA renderers want these to treat the stream as seekable.
            if (headers.containsKey("getcontentfeatures.dlna.org")) h["contentFeatures.dlna.org"] = Dlna.CONTENT_FEATURES
            h["transferMode.dlna.org"] = "Streaming"
            if (len >= 0) h["Content-Length"] = len.toString()
            if (range != null && size >= 0) h["Content-Range"] = "bytes $start-$end/$size"
            respond(out, if (range != null) "206 Partial Content" else "200 OK", h)
            if (head) return
            src.open(start).use { input ->
                val buf = ByteArray(256 * 1024); var left = if (len >= 0) len else Long.MAX_VALUE
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n <= 0) break
                    out.write(buf, 0, n); left -= n
                }
                out.flush()
            }
        } catch (_: Exception) { /* client closed (seek) */ } finally { runCatching { src.close() } }
    }

    private fun respond(out: OutputStream, status: String, h: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $status\r\n"); h.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        if (h.isEmpty()) sb.append("Content-Length: 0\r\nConnection: close\r\n")
        out.write(sb.append("\r\n").toString().toByteArray(Charsets.ISO_8859_1)); out.flush()
    }

    companion object {
        fun mime(name: String) = when (name.substringAfterLast('.').lowercase()) {
            "mp4", "m4v" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "avi" -> "video/x-msvideo"
            "srt" -> "application/x-subrip"; "vtt" -> "text/vtt"; "ass", "ssa" -> "text/x-ssa"; else -> "application/octet-stream"
        }
    }
}

object Range {
    fun parse(h: String?): Pair<Long, Long?>? {
        val m = Regex("""^bytes=(\d+)-(\d*)$""").matchEntire(h?.trim() ?: return null) ?: return null
        return m.groupValues[1].toLong() to m.groupValues[2].toLongOrNull()
    }
}
