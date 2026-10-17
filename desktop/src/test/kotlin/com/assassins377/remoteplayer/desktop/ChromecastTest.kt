package com.assassins377.remoteplayer.desktop

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.util.Collections
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

class ChromecastTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun protobufCodec() {
        val m = CastMessage("sender-0", "receiver-0", CastClient.NS_CONN, """{"type":"CONNECT","x":"ü"}""")
        val b = m.encode()
        assertArrayEquals(byteArrayOf(0x08, 0x00, 0x12, 8), b.copyOfRange(0, 4)) // field1=0, field2 len 8
        assertEquals(m, CastMessage.decode(b))
        val long = m.copy(payload = "x".repeat(300))           // multi-byte varint length
        assertEquals(long, CastMessage.decode(long.encode()))
    }

    @Test fun mimeMapping() {
        assertEquals("video/mp4", receiverMime("video/x-matroska")); assertEquals("video/webm", receiverMime("video/webm"))
    }

    /** Fake Chromecast: TLS on a random port with a self-signed cert, answering the default media receiver protocol. */
    private inner class FakeDevice {
        val received: MutableList<Pair<String, JsonObject>> = Collections.synchronizedList(mutableListOf())
        val server: SSLServerSocket
        init {
            val ks = File(tmp.root, "dev.p12")
            val keytool = File(System.getProperty("java.home"), "bin/keytool" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
            val p = ProcessBuilder(keytool.path, "-genkeypair", "-alias", "d", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=fake-cast",
                "-validity", "2", "-storetype", "PKCS12", "-keystore", ks.path, "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).start()
            p.inputStream.readBytes(); assertEquals(0, p.waitFor())
            val store = KeyStore.getInstance("PKCS12").apply { ks.inputStream().use { load(it, "changeit".toCharArray()) } }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "changeit".toCharArray()) }
            val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
            server = ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket
            Thread { runCatching { serve() } }.apply { isDaemon = true }.start()
        }
        private var time = 0.0
        private fun serve() = server.accept().use { s ->
            val inp = DataInputStream(s.getInputStream()); val out = DataOutputStream(s.getOutputStream())
            fun reply(to: CastMessage, o: JsonObject) { val b = CastMessage(to.destination, to.source, to.namespace, o.toString()).encode(); synchronized(out) { out.writeInt(b.size); out.write(b); out.flush() } }
            while (true) {
                val m = CastMessage.decode(ByteArray(inp.readInt()).also { inp.readFully(it) })
                val o = Json.parseToJsonElement(m.payload).jsonObject
                received += m.namespace to o
                val id = o["requestId"]?.jsonPrimitive?.int
                fun media(state: String) = buildJsonObject { put("type", "MEDIA_STATUS"); id?.let { put("requestId", it) }
                    putJsonArray("status") { addJsonObject { put("mediaSessionId", 7); put("playerState", state); put("currentTime", time); putJsonObject("media") { put("duration", 90.0) } } } }
                when (o["type"]?.jsonPrimitive?.content) {
                    "LAUNCH" -> reply(m, buildJsonObject { put("type", "RECEIVER_STATUS"); put("requestId", id!!)
                        putJsonObject("status") { putJsonArray("applications") { addJsonObject { put("appId", "CC1AD845"); put("transportId", "web-5"); put("sessionId", "sess-1") } } } })
                    "LOAD" -> { time = o["currentTime"]!!.jsonPrimitive.double; reply(m, media("PLAYING")) }
                    "SEEK" -> { time = o["currentTime"]!!.jsonPrimitive.double; reply(m, media("PLAYING")) }
                    "PAUSE" -> reply(m, media("PAUSED"))
                    "PLAY", "GET_STATUS" -> reply(m, media("PLAYING"))
                    "STOP" -> reply(m, media("IDLE"))
                }
            }
        }
    }

    @Test fun defaultMediaReceiverFlowAgainstFakeDevice() {
        val dev = FakeDevice()
        val t = ChromecastTarget(CastDevice("Fake TV", "127.0.0.1", dev.server.localPort))
        t.play("http://192.168.1.2:4000/tok/a.mkv", "Film", "video/x-matroska", 12_000)
        assertEquals(12_000L to 90_000L, t.position())
        t.pause(); t.resume(); t.seek(30_000)
        assertEquals(30_000L, t.position().first)
        t.stop()
        Thread.sleep(200)
        val types = dev.received.map { it.second["type"]?.jsonPrimitive?.content }
        assertEquals(listOf("CONNECT", "LAUNCH", "CONNECT", "LOAD", "GET_STATUS", "PAUSE", "PLAY", "SEEK", "GET_STATUS", "STOP"), types.filter { it != "PING" && it != "CLOSE" }.take(10))
        val load = dev.received.first { it.second["type"]?.jsonPrimitive?.content == "LOAD" }
        assertEquals(CastClient.NS_MEDIA, load.first)
        val media = load.second["media"]!!.jsonObject
        assertEquals("http://192.168.1.2:4000/tok/a.mkv", media["contentId"]!!.jsonPrimitive.content)
        assertEquals("video/mp4", media["contentType"]!!.jsonPrimitive.content)
        assertEquals("sess-1", load.second["sessionId"]!!.jsonPrimitive.content)
        assertEquals(7, dev.received.first { it.second["type"]?.jsonPrimitive?.content == "PAUSE" }.second["mediaSessionId"]!!.jsonPrimitive.int)
    }
}
