package com.assassins377.remoteplayer.cast

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import com.assassins377.remoteplayer.player.RoutingDataSourceFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Tiny LAN HTTP server that re-serves any playback uri (SMB / WebDAV / HTTP with auth) as plain HTTP with Range
 * support, so a Chromecast or DLNA TV can fetch it. URLs are unguessable tokens; only registered uris are served.
 */
@OptIn(UnstableApi::class)
class StreamProxy(private val sourceFactory: () -> DataSource) {
    private var server: ServerSocket? = null
    private val tokens = ConcurrentHashMap<String, Pair<String, String>>() // token -> (uri, mime)
    val port: Int get() = server?.localPort ?: 0

    @Synchronized fun start(): StreamProxy {
        if (server != null) return this
        val s = ServerSocket(0, 16, null as InetAddress?)
        server = s
        thread(name = "stream-proxy", isDaemon = true) {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(c) }; runCatching { c.close() } }
            }
        }
        return this
    }

    @Synchronized fun stop() { runCatching { server?.close() }; server = null; tokens.clear() }

    /** Registers [uri] and returns the path "/s/<token>/<name>". */
    fun register(uri: String, name: String, mime: String): String {
        val t = UUID.randomUUID().toString().replace("-", "")
        tokens[t] = uri to mime
        return "/s/$t/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
    }

    fun url(host: String, path: String) = "http://$host:$port$path"

    private fun handle(c: Socket) {
        c.soTimeout = 30_000
        val reader = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.ISO_8859_1))
        val request = reader.readLine() ?: return
        val headers = HashMap<String, String>()
        while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break; val i = l.indexOf(':'); if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim() }
        val parts = request.split(' ')
        val method = parts.getOrNull(0) ?: return
        val token = parts.getOrNull(1)?.split('/')?.getOrNull(2)
        val out = c.getOutputStream()
        val entry = token?.let { tokens[it] } ?: return respond(out, "404 Not Found", emptyMap())
        val (uri, mime) = entry
        val range = Ranges.parse(headers["range"])
        val ds = sourceFactory()
        try {
            val start = range?.first ?: 0L
            val len = ds.open(DataSpec.Builder().setUri(android.net.Uri.parse(uri)).setPosition(start).build())
            val total = if (len == C.LENGTH_UNSET.toLong()) -1 else start + len
            val end = range?.second?.let { e -> if (total > 0) minOf(e, total - 1) else e } ?: (total - 1)
            val bodyLen = if (end >= start) end - start + 1 else -1
            val h = linkedMapOf("Content-Type" to mime, "Accept-Ranges" to "bytes", "Connection" to "close",
                "transferMode.dlna.org" to "Streaming", "contentFeatures.dlna.org" to "DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000")
            if (bodyLen >= 0) h["Content-Length"] = bodyLen.toString()
            if (range != null && total > 0) h["Content-Range"] = "bytes $start-$end/$total"
            respond(out, if (range != null) "206 Partial Content" else "200 OK", h)
            if (method == "HEAD") return
            val buf = ByteArray(256 * 1024); var left = if (bodyLen >= 0) bodyLen else Long.MAX_VALUE
            while (left > 0) {
                val n = ds.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, n); left -= n
            }
            out.flush()
        } finally { runCatching { ds.close() } }
    }

    private fun respond(out: OutputStream, status: String, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $status\r\n"); headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        if (headers.isEmpty()) sb.append("Content-Length: 0\r\nConnection: close\r\n")
        out.write(sb.append("\r\n").toString().toByteArray(Charsets.ISO_8859_1)); out.flush()
    }

    companion object {
        @Volatile private var shared: StreamProxy? = null
        fun shared(ctx: Context): StreamProxy = shared ?: synchronized(this) {
            shared ?: StreamProxy { RoutingDataSourceFactory(DefaultDataSource.Factory(ctx.applicationContext)).createDataSource() }.start().also { shared = it }
        }

        /** First site-local IPv4 address (Wi-Fi / Ethernet), e.g. 192.168.1.23. */
        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()

        fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"; "ts" -> "video/mp2t"; "wmv" -> "video/x-ms-wmv"; else -> "video/mp4"
        }
    }
}

object Ranges {
    /** "bytes=100-" → (100, null); "bytes=100-199" → (100, 199); suffix/multi ranges unsupported → null. */
    fun parse(h: String?): Pair<Long, Long?>? {
        val m = Regex("""^bytes=(\d+)-(\d*)$""").matchEntire(h?.trim() ?: return null) ?: return null
        return m.groupValues[1].toLong() to m.groupValues[2].toLongOrNull()
    }
}
