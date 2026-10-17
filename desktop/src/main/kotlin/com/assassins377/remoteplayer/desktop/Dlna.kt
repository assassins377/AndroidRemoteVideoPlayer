package com.assassins377.remoteplayer.desktop

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.net.*
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/** A UPnP MediaRenderer with an AVTransport service. */
data class Renderer(val name: String, val location: String, val avTransportControl: String, val renderingControl: String?) {
    val host: String get() = URI(location).host
}

/**
 * Minimal DLNA/UPnP control point: SSDP discovery (M-SEARCH on 239.255.255.250:1900), device description parsing
 * and AVTransport SOAP actions. Media is served by a LAN [StreamServer].
 */
object Dlna {
    const val CONTENT_FEATURES = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
    private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val RC = "urn:schemas-upnp-org:service:RenderingControl:1"
    private val http = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()

    /** LOCATION header of an SSDP response, if it advertises a renderer/AVTransport. */
    fun parseSsdp(response: String): String? {
        val h = response.lines().drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val st = h["st"] ?: h["nt"] ?: ""
        return h["location"]?.takeIf { st.contains("MediaRenderer") || st.contains("AVTransport") || st == "ssdp:all" || st.startsWith("upnp:rootdevice") }
    }

    fun discover(timeoutMs: Int = 3000): List<Renderer> {
        val locations = linkedSetOf<String>()
        DatagramSocket().use { sock ->
            sock.soTimeout = 300
            val group = InetAddress.getByName("239.255.255.250")
            listOf("urn:schemas-upnp-org:device:MediaRenderer:1", AVT).forEach { st ->
                val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n".toByteArray()
                runCatching { sock.send(DatagramPacket(msg, msg.size, group, 1900)) }
            }
            val end = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < end) {
                val p = DatagramPacket(buf, buf.size)
                try { sock.receive(p) } catch (_: SocketTimeoutException) { continue }
                parseSsdp(String(p.data, 0, p.length, Charsets.UTF_8))?.let(locations::add)
            }
        }
        return locations.mapNotNull { runCatching { describe(it) }.getOrNull() }.distinctBy { it.avTransportControl }
    }

    /** Fetches and parses a device description; null if it has no AVTransport. */
    fun describe(location: String): Renderer? {
        val xml = http.newCall(Request.Builder().url(location).build()).execute().use { it.body!!.string() }
        return parseDescription(location, xml)
    }

    fun parseDescription(location: String, xml: String): Renderer? {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder().parse(xml.byteInputStream())
        fun Element.text(tag: String) = getElementsByTagName(tag).item(0)?.textContent?.trim()
        val root = doc.documentElement
        val base = root.text("URLBase")?.takeIf { it.isNotBlank() } ?: location
        val services = root.getElementsByTagName("service")
        var avt: String? = null; var rc: String? = null
        for (i in 0 until services.length) {
            val s = services.item(i) as Element
            val url = s.text("controlURL") ?: continue
            when (s.text("serviceType")?.substringBeforeLast(':')) {
                AVT.substringBeforeLast(':') -> avt = URI(base).resolve(url).toString()
                RC.substringBeforeLast(':') -> rc = URI(base).resolve(url).toString()
            }
        }
        return avt?.let { Renderer(root.text("friendlyName") ?: URI(location).host, location, it, rc) }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun soap(controlUrl: String, service: String, action: String, args: Map<String, String>): String {
        val body = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""")
            append("<u:$action xmlns:u=\"$service\">")
            args.forEach { (k, v) -> append("<$k>${esc(v)}</$k>") }
            append("</u:$action></s:Body></s:Envelope>")
        }
        val req = Request.Builder().url(controlUrl).header("SOAPACTION", "\"$service#$action\"")
            .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType())).build()
        return http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw java.io.IOException("$action: HTTP ${r.code}")
            text
        }
    }

    fun didl(url: String, title: String, mime: String) = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>${esc(title)}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:$mime:$CONTENT_FEATURES">${esc(url)}</res></item></DIDL-Lite>"""

    fun play(r: Renderer, url: String, title: String, mime: String, startMs: Long = 0) {
        runCatching { soap(r.avTransportControl, AVT, "Stop", mapOf("InstanceID" to "0")) }
        soap(r.avTransportControl, AVT, "SetAVTransportURI", mapOf("InstanceID" to "0", "CurrentURI" to url, "CurrentURIMetaData" to didl(url, title, mime)))
        soap(r.avTransportControl, AVT, "Play", mapOf("InstanceID" to "0", "Speed" to "1"))
        if (startMs > 5000) runCatching { seek(r, startMs) }
    }
    fun pause(r: Renderer) { soap(r.avTransportControl, AVT, "Pause", mapOf("InstanceID" to "0")) }
    fun resume(r: Renderer) { soap(r.avTransportControl, AVT, "Play", mapOf("InstanceID" to "0", "Speed" to "1")) }
    fun stop(r: Renderer) { soap(r.avTransportControl, AVT, "Stop", mapOf("InstanceID" to "0")) }
    fun seek(r: Renderer, ms: Long) { soap(r.avTransportControl, AVT, "Seek", mapOf("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to hms(ms))) }

    /** (position, duration) in ms. */
    fun position(r: Renderer): Pair<Long, Long> {
        val x = soap(r.avTransportControl, AVT, "GetPositionInfo", mapOf("InstanceID" to "0"))
        fun tag(t: String) = Regex("<$t>([^<]*)</$t>").find(x)?.groupValues?.get(1)
        return parseHms(tag("RelTime")) to parseHms(tag("TrackDuration"))
    }

    fun hms(ms: Long): String { val t = ms / 1000; return "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) }
    fun parseHms(s: String?): Long = s?.split(':')?.takeIf { it.size == 3 }?.let { (h, m, sec) ->
        ((h.toLongOrNull() ?: 0) * 3600 + (m.toLongOrNull() ?: 0) * 60) * 1000 + ((sec.toDoubleOrNull() ?: 0.0) * 1000).toLong()
    } ?: 0L

    /** Local address the renderer can reach (route towards its host). */
    fun localAddressFor(host: String): String = DatagramSocket().use { it.connect(InetAddress.getByName(host), 9); it.localAddress.hostAddress }
}
