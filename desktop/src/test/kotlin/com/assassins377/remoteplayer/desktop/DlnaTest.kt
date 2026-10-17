package com.assassins377.remoteplayer.desktop

import com.assassins377.remoteplayer.data.ServerConfig
import com.assassins377.remoteplayer.data.ServerType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class DlnaTest {
    private val desc = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device><friendlyName>Living Room TV</friendlyName>
        <serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/upnp/control/avt</controlURL></service>
        <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>rc</controlURL></service></serviceList></device></root>"""

    @Test fun ssdpAndHms() {
        assertEquals("http://10.0.0.5:8080/desc.xml", Dlna.parseSsdp("HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nLOCATION: http://10.0.0.5:8080/desc.xml\r\n\r\n"))
        assertNull(Dlna.parseSsdp("HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\nLOCATION: http://x/\r\n"))
        assertEquals("1:02:03", Dlna.hms(3_723_000)); assertEquals(3_723_500, Dlna.parseHms("1:02:03.500"))
    }

    @Test fun describeAndControl() {
        val soap = mutableListOf<RecordedRequest>()
        val mws = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when (request.path) {
                    "/dev/desc.xml" -> MockResponse().setBody(desc)
                    else -> { synchronized(soap) { soap += request }
                        MockResponse().setBody("<s:Envelope><s:Body><u:R><RelTime>0:00:42</RelTime><TrackDuration>0:01:30</TrackDuration></u:R></s:Body></s:Envelope>") }
                }
            }
            start()
        }
        val r = Dlna.describe(mws.url("/dev/desc.xml").toString())!!
        assertEquals("Living Room TV", r.name)
        assertEquals(mws.url("/upnp/control/avt").toString(), r.avTransportControl)
        assertEquals(mws.url("/dev/rc").toString(), r.renderingControl)
        Dlna.play(r, "http://192.168.1.2:1234/tok/a&b.mkv", "A & B", "video/x-matroska", startMs = 60_000)
        val actions = soap.map { it.getHeader("SOAPACTION")!!.substringAfter('#').trim('"') }
        assertEquals(listOf("Stop", "SetAVTransportURI", "Play", "Seek"), actions)
        val set = soap[1].body.readUtf8()
        assertTrue(set.contains("a&amp;b.mkv")); assertTrue(set.contains("&lt;dc:title&gt;A &amp;amp; B"))
        assertEquals(42_000L to 90_000L, Dlna.position(r))
        mws.shutdown()
    }

    @Test fun lanStreamServerServesRangesWithDlnaHeaders() {
        val web = ServerConfig("w", "W", ServerType.HTTP, "http://127.0.0.1:8082/video/")
        val up = runCatching { (URL("http://127.0.0.1:8082/video/").openConnection() as HttpURLConnection).responseCode }.getOrNull()
        org.junit.Assume.assumeTrue("local test HTTP server not running", up == 200)
        val ss = StreamServer(null).start()
        val url = ss.register(web, "Movies/Test Clip.mkv", "127.0.0.1")
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Range", "bytes=10-19"); c.setRequestProperty("getcontentFeatures.dlna.org", "1")
        assertEquals(206, c.responseCode)
        assertEquals("Streaming", c.getHeaderField("transferMode.dlna.org"))
        assertEquals(Dlna.CONTENT_FEATURES, c.getHeaderField("contentFeatures.dlna.org"))
        assertEquals(10, c.inputStream.readBytes().size)
    }

    /** Opt-in end-to-end test against a real renderer on the LAN (e.g. `gmediarender -f TestRenderer`). */
    @Test fun endToEndWithRealRenderer() {
        val name = System.getenv("REMOTEPLAYER_DLNA_E2E")
        org.junit.Assume.assumeTrue("set REMOTEPLAYER_DLNA_E2E=<renderer name>", !name.isNullOrBlank())
        val r = Dlna.discover(4000).firstOrNull { it.name == name } ?: error("renderer $name not discovered")
        val web = ServerConfig("w", "W", ServerType.HTTP, "http://127.0.0.1:8082/video/")
        val ss = StreamServer(null).start()
        Dlna.play(r, ss.register(web, "Movies/Test Clip.mkv", Dlna.localAddressFor(r.host)), "Test Clip", "video/x-matroska")
        var pos = 0L
        repeat(30) { if (pos < 2000) { Thread.sleep(500); pos = runCatching { Dlna.position(r).first }.getOrDefault(0L) } }
        Dlna.stop(r)
        assertTrue("renderer did not advance: $pos", pos >= 2000)
    }
}
