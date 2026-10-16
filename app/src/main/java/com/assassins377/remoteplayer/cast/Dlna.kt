package com.assassins377.remoteplayer.cast

import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URL

data class DlnaRenderer(val name: String, val controlUrl: String, val location: String)

/** Minimal UPnP/DLNA MediaRenderer control: SSDP discovery, SetAVTransportURI, Play, Stop. */
object Dlna {
    private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"

    /** Blocking; call on IO. */
    fun discover(timeoutMs: Int = 2500): List<DlnaRenderer> {
        val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $AVT\r\n\r\n".toByteArray()
        val locations = LinkedHashSet<String>()
        MulticastSocket(null).use { sock ->
            sock.reuseAddress = true; sock.soTimeout = 400; sock.bind(null)
            val group = InetAddress.getByName("239.255.255.250")
            repeat(2) { sock.send(DatagramPacket(msg, msg.size, group, 1900)) }
            val end = System.currentTimeMillis() + timeoutMs; val buf = ByteArray(4096)
            while (System.currentTimeMillis() < end) {
                val p = DatagramPacket(buf, buf.size)
                try { sock.receive(p) } catch (_: SocketTimeoutException) { continue }
                parseLocation(String(p.data, 0, p.length))?.let { locations += it }
            }
        }
        return locations.mapNotNull { loc -> runCatching { describe(loc) }.getOrNull() }
    }

    fun parseLocation(response: String): String? =
        response.lineSequence().firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }?.substringAfter(':')?.trim()

    private fun describe(location: String): DlnaRenderer? {
        val xml = (URL(location).openConnection() as HttpURLConnection).run { connectTimeout = 2000; readTimeout = 3000; inputStream.use { it.readBytes().decodeToString() } }
        return parseDescription(xml, location)
    }

    /** Friendly name + absolute AVTransport control URL from a device description. */
    fun parseDescription(xml: String, location: String): DlnaRenderer? {
        val name = Regex("<friendlyName>(.*?)</friendlyName>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim() ?: "DLNA"
        val svc = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1] }
            .firstOrNull { it.contains("AVTransport") } ?: return null
        val ctrl = Regex("<controlURL>(.*?)</controlURL>", RegexOption.DOT_MATCHES_ALL).find(svc)?.groupValues?.get(1)?.trim() ?: return null
        val base = Regex("<URLBase>(.*?)</URLBase>").find(xml)?.groupValues?.get(1)?.trim() ?: location
        return DlnaRenderer(unescape(name), URL(URL(base), ctrl).toString(), location)
    }

    fun play(r: DlnaRenderer, mediaUrl: String, title: String, mime: String) {
        val meta = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${escape(title)}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class><res protocolInfo=\"http-get:*:$mime:*\">${escape(mediaUrl)}</res></item></DIDL-Lite>"
        soap(r, "SetAVTransportURI", "<CurrentURI>${escape(mediaUrl)}</CurrentURI><CurrentURIMetaData>${escape(meta)}</CurrentURIMetaData>")
        soap(r, "Play", "<Speed>1</Speed>")
    }

    fun stop(r: DlnaRenderer) { runCatching { soap(r, "Stop", "") } }
    fun pause(r: DlnaRenderer) { runCatching { soap(r, "Pause", "") } }
    fun resume(r: DlnaRenderer) { runCatching { soap(r, "Play", "<Speed>1</Speed>") } }

    fun envelope(action: String, args: String) =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$AVT\"><InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"

    private fun soap(r: DlnaRenderer, action: String, args: String) {
        val body = envelope(action, args).toByteArray()
        val c = (URL(r.controlUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 3000; readTimeout = 5000
            setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            setRequestProperty("SOAPACTION", "\"$AVT#$action\"")
        }
        try { c.outputStream.use { it.write(body) }; val code = c.responseCode; if (code !in 200..299) throw java.io.IOException("DLNA $action: HTTP $code") } finally { c.disconnect() }
    }

    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
}
