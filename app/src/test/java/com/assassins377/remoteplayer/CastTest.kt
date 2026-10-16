package com.assassins377.remoteplayer

import androidx.media3.datasource.DefaultHttpDataSource
import com.assassins377.remoteplayer.cast.Dlna
import com.assassins377.remoteplayer.cast.Ranges
import com.assassins377.remoteplayer.cast.StreamProxy
import com.assassins377.remoteplayer.ui.TestApp
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class CastTest {
    @Test fun ranges() {
        assertEquals(100L to null, Ranges.parse("bytes=100-")); assertEquals(0L to 99L, Ranges.parse("bytes=0-99"))
        assertNull(Ranges.parse("bytes=-500")); assertNull(Ranges.parse(null))
    }

    @Test fun dlnaParsing() {
        assertEquals("http://192.168.1.5:9197/dmr", Dlna.parseLocation("HTTP/1.1 200 OK\r\nST: x\r\nLocation: http://192.168.1.5:9197/dmr\r\n\r\n"))
        val xml = """<root><device><friendlyName>[TV] Samsung &amp; Co</friendlyName><serviceList>
            <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/rc</controlURL></service>
            <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/upnp/control/AVTransport1</controlURL></service>
            </serviceList></device></root>"""
        val r = Dlna.parseDescription(xml, "http://192.168.1.5:9197/dmr")!!
        assertEquals("[TV] Samsung & Co", r.name); assertEquals("http://192.168.1.5:9197/upnp/control/AVTransport1", r.controlUrl)
        assertTrue(Dlna.envelope("Play", "<Speed>1</Speed>").contains("<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><InstanceID>0</InstanceID><Speed>1</Speed>"))
        assertEquals("a&amp;b&lt;", Dlna.escape("a&b<"))
    }

    /** Proxy re-serves a source with Range (uses nginx test server). */
    @Test fun proxyServesRanges() {
        val base = System.getenv("HTTP_IT_URL"); val local = File("/workspace/smbshare/Movies/Test Clip.mkv")
        assumeTrue(base != null && local.exists())
        val proxy = StreamProxy { DefaultHttpDataSource.Factory().createDataSource() }.start()
        try {
            val url = proxy.url("127.0.0.1", proxy.register(base + "Movies/Test%20Clip.mkv", "Test Clip.mkv", "video/x-matroska"))
            val c = URL(url).openConnection() as HttpURLConnection
            c.setRequestProperty("Range", "bytes=1000-1999")
            assertEquals(206, c.responseCode)
            assertEquals("bytes 1000-1999/${local.length()}", c.getHeaderField("Content-Range"))
            val body = c.inputStream.use { it.readBytes() }
            assertArrayEquals(local.readBytes().copyOfRange(1000, 2000), body)
            val head = URL(url).openConnection() as HttpURLConnection; head.requestMethod = "HEAD"
            assertEquals(200, head.responseCode); assertEquals(local.length().toString(), head.getHeaderField("Content-Length"))
            assertEquals(404, (URL("http://127.0.0.1:${proxy.port}/s/nope/x").openConnection() as HttpURLConnection).responseCode)
        } finally { proxy.stop() }
    }
}
