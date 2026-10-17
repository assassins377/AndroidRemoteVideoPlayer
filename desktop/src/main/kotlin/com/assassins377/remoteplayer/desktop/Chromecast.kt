package com.assassins377.remoteplayer.desktop

import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.jmdns.JmDNS
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** A Chromecast found via mDNS (_googlecast._tcp). */
data class CastDevice(val name: String, val host: String, val port: Int = 8009, val model: String? = null)

/**
 * CASTV2 CastMessage (cast_channel.proto) with a hand-written protobuf codec: the message has only seven scalar fields,
 * so no protobuf runtime is needed.
 */
data class CastMessage(val source: String, val destination: String, val namespace: String, val payload: String) {
    fun encode(): ByteArray {
        val o = ByteArrayOutputStream()
        fun varint(v: Long) { var x = v; while (x and 0x7FL.inv() != 0L) { o.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }; o.write(x.toInt()) }
        fun str(field: Int, s: String) { val b = s.toByteArray(Charsets.UTF_8); varint(((field shl 3) or 2).toLong()); varint(b.size.toLong()); o.write(b) }
        varint((1 shl 3).toLong()); varint(0)            // protocol_version = CASTV2_1_0
        str(2, source); str(3, destination); str(4, namespace)
        varint((5 shl 3).toLong()); varint(0)            // payload_type = STRING
        str(6, payload)
        return o.toByteArray()
    }

    companion object {
        fun decode(b: ByteArray): CastMessage {
            var i = 0
            fun varint(): Long { var r = 0L; var shift = 0; while (true) { val x = b[i++].toInt() and 0xff; r = r or ((x and 0x7f).toLong() shl shift); if (x < 0x80) return r; shift += 7 } }
            val strings = HashMap<Int, String>()
            while (i < b.size) {
                val key = varint().toInt(); val field = key ushr 3
                when (key and 7) {
                    0 -> varint()
                    2 -> { val n = varint().toInt(); strings[field] = String(b, i, n, Charsets.UTF_8); i += n }
                    5 -> i += 4
                    1 -> i += 8
                    else -> throw IOException("bad wire type")
                }
            }
            return CastMessage(strings[2].orEmpty(), strings[3].orEmpty(), strings[4].orEmpty(), strings[6].orEmpty())
        }
    }
}

/**
 * Minimal CASTV2 sender: CONNECT, heartbeat, LAUNCH of the Default Media Receiver (CC1AD845), then
 * LOAD/PLAY/PAUSE/SEEK/STOP/GET_STATUS on the media namespace. Frames are 4-byte big-endian length + CastMessage.
 */
class CastClient(private val socket: Socket) : AutoCloseable {
    private val out = DataOutputStream(socket.getOutputStream())
    private val inp = DataInputStream(socket.getInputStream())
    private val reqId = AtomicInteger(1)
    private val inbox = LinkedBlockingQueue<CastMessage>()
    @Volatile private var closed = false
    private var transportId: String? = null
    private var sessionId: String? = null
    private var mediaSessionId: Long? = null
    @Volatile var lastStatus: Pair<Long, Long> = 0L to 0L; private set

    init {
        Thread({ readLoop() }, "cast-read").apply { isDaemon = true }.start()
        send(RECEIVER, NS_CONN, buildJsonObject { put("type", "CONNECT") })
        Thread({ while (!closed) { runCatching { send(RECEIVER, NS_HEARTBEAT, buildJsonObject { put("type", "PING") }) }; Thread.sleep(5000) } }, "cast-ping").apply { isDaemon = true }.start()
    }

    @Synchronized private fun send(dest: String, ns: String, payload: JsonObject) {
        val b = CastMessage(SENDER, dest, ns, payload.toString()).encode()
        out.writeInt(b.size); out.write(b); out.flush()
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val n = inp.readInt(); if (n <= 0 || n > 1 shl 20) throw IOException("bad frame $n")
                val m = CastMessage.decode(ByteArray(n).also { inp.readFully(it) })
                val type = runCatching { Json.parseToJsonElement(m.payload).jsonObject["type"]?.jsonPrimitive?.content }.getOrNull()
                if (m.namespace == NS_HEARTBEAT && type == "PING") send(m.source, NS_HEARTBEAT, buildJsonObject { put("type", "PONG") })
                else { if (m.namespace == NS_MEDIA) updateMedia(m.payload); inbox.put(m) }
            }
        } catch (_: Exception) { closed = true }
    }

    private fun updateMedia(payload: String) = runCatching {
        val st = Json.parseToJsonElement(payload).jsonObject["status"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@runCatching
        st["mediaSessionId"]?.jsonPrimitive?.longOrNull?.let { mediaSessionId = it }
        val pos = ((st["currentTime"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong()
        val dur = ((st["media"]?.jsonObject?.get("duration")?.jsonPrimitive?.doubleOrNull ?: (lastStatus.second / 1000.0)) * 1000).toLong()
        lastStatus = pos to dur
    }

    /** Sends a request and waits for the reply with the same requestId (or the given types). */
    private fun request(dest: String, ns: String, body: JsonObjectBuilder.() -> Unit, timeoutMs: Long = 10_000): JsonObject {
        val id = reqId.getAndIncrement()
        send(dest, ns, buildJsonObject { body(); put("requestId", id) })
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = end - System.currentTimeMillis(); if (left <= 0 || closed) throw IOException("Chromecast: no reply")
            val m = inbox.poll(left, TimeUnit.MILLISECONDS) ?: continue
            val o = runCatching { Json.parseToJsonElement(m.payload).jsonObject }.getOrNull() ?: continue
            if (o["requestId"]?.jsonPrimitive?.intOrNull == id) {
                val t = o["type"]?.jsonPrimitive?.content
                if (t in setOf("LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST", "LAUNCH_ERROR")) throw IOException("Chromecast: $t")
                return o
            }
        }
    }

    private fun ensureApp() {
        if (transportId != null) return
        val st = request(RECEIVER, NS_RECEIVER, { put("type", "LAUNCH"); put("appId", DEFAULT_MEDIA_RECEIVER) }, 20_000)
        val app = st["status"]?.jsonObject?.get("applications")?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { it["appId"]?.jsonPrimitive?.content == DEFAULT_MEDIA_RECEIVER } ?: throw IOException("Chromecast: receiver not started")
        transportId = app["transportId"]!!.jsonPrimitive.content
        sessionId = app["sessionId"]?.jsonPrimitive?.content
        send(transportId!!, NS_CONN, buildJsonObject { put("type", "CONNECT") })
    }

    fun load(url: String, title: String, mime: String, startMs: Long) {
        ensureApp()
        request(transportId!!, NS_MEDIA, {
            put("type", "LOAD"); sessionId?.let { put("sessionId", it) }; put("autoplay", true); put("currentTime", startMs / 1000.0)
            putJsonObject("media") {
                put("contentId", url); put("contentType", mime); put("streamType", "BUFFERED")
                putJsonObject("metadata") { put("metadataType", 0); put("title", title) }
            }
        }, 30_000)
    }

    private fun media(type: String, extra: JsonObjectBuilder.() -> Unit = {}) {
        val t = transportId ?: throw IOException("Chromecast: nothing loaded")
        val ms = mediaSessionId ?: throw IOException("Chromecast: no media session")
        request(t, NS_MEDIA, { put("type", type); put("mediaSessionId", ms); extra() })
    }

    fun play() = media("PLAY")
    fun pause() = media("PAUSE")
    fun seek(ms: Long) = media("SEEK") { put("currentTime", ms / 1000.0) }
    fun stopMedia() = media("STOP")
    fun status(): Pair<Long, Long> {
        transportId?.let { t -> runCatching { request(t, NS_MEDIA, { put("type", "GET_STATUS"); mediaSessionId?.let { put("mediaSessionId", it) } }, 5000) } }
        return lastStatus
    }

    override fun close() {
        closed = true
        runCatching { transportId?.let { send(it, NS_CONN, buildJsonObject { put("type", "CLOSE") }) } }
        runCatching { socket.close() }
    }

    companion object {
        const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"
        const val SENDER = "sender-0"
        const val RECEIVER = "receiver-0"
        const val NS_CONN = "urn:x-cast:com.google.cast.tp.connection"
        const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        const val NS_MEDIA = "urn:x-cast:com.google.cast.media"

        /** Chromecasts present self-signed device certificates, so the TLS channel is not authenticated (as in all CASTV2 senders). */
        fun connect(host: String, port: Int = 8009, timeoutMs: Int = 5000): CastClient {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
                override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
            }), null)
            val raw = Socket().apply { connect(InetSocketAddress(host, port), timeoutMs) }
            val tls = ctx.socketFactory.createSocket(raw, host, port, true)
            return CastClient(tls)
        }

        /** Chromecasts on the LAN via mDNS. */
        fun discover(timeoutMs: Long = 3000): List<CastDevice> = runCatching {
            JmDNS.create().use { mdns ->
                mdns.list("_googlecast._tcp.local.", timeoutMs).mapNotNull { info ->
                    val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: info.hostAddresses.firstOrNull() ?: return@mapNotNull null
                    CastDevice(info.getPropertyString("fn") ?: info.name, host, info.port.takeIf { it > 0 } ?: 8009, info.getPropertyString("md"))
                }.distinctBy { it.host to it.port }
            }
        }.getOrDefault(emptyList())
    }
}
