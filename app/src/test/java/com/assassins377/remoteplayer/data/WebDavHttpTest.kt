package com.assassins377.remoteplayer.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class UrlPathsTest {
    @Test fun encodesCyrillicAndSpaces() {
        assertEquals("http://nas/dav/%D0%A4%D0%B8%D0%BB%D1%8C%D0%BC%D1%8B/My%20Movie%20%282020%29.mkv",
            UrlPaths.join("http://nas/dav/", "Фильмы/My Movie (2020).mkv"))
        assertEquals("http://nas/dav/", UrlPaths.join("http://nas/dav", ""))
        assertEquals("http://nas/a%2Bb/", UrlPaths.join("http://nas", "a+b", dir = true))
    }

    @Test fun hrefDecodingAndRelative() {
        assertEquals("dav/Фильмы/A B.mkv", UrlPaths.hrefPath("/dav/%D0%A4%D0%B8%D0%BB%D1%8C%D0%BC%D1%8B/A%20B.mkv"))
        assertEquals("Фильмы/x.mkv", UrlPaths.relative("dav", "http://nas/dav/%D0%A4%D0%B8%D0%BB%D1%8C%D0%BC%D1%8B/x.mkv"))
        assertNull(UrlPaths.relative("dav", "/other/x.mkv"))
        assertEquals("", UrlPaths.relative("dav", "/dav/"))
    }

    @Test fun httpUriRoundTripWithoutCredentials() {
        val u = HttpUri.build("srv 1", "Фильмы/My Movie.mkv")
        assertTrue(u.startsWith("rvp-http://")); assertFalse(u.contains("@"))
        assertEquals("srv 1" to "Фильмы/My Movie.mkv", HttpUri.parse(u))
        assertEquals(PlaybackRef("srv 1", "Фильмы/My Movie.mkv", true), PlaybackRef.parse(u))
        assertEquals(u.replace("My%20Movie.mkv", "B.srt"), PlaybackRef.parse(u)!!.withPath("Фильмы/B.srt"))
    }
}

class PropfindParserTest {
    private val xml = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:" xmlns:Z="urn:schemas-microsoft-com:">
 <D:response><D:href>/dav/Movies/</D:href>
  <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
 <D:response><D:href>/dav/Movies/%D0%91%D1%80%D0%B0%D1%82%201997.mkv</D:href>
  <D:propstat><D:prop><D:resourcetype/><D:getcontentlength>16095208</D:getcontentlength>
   <D:getlastmodified>Thu, 15 Oct 2026 14:54:00 GMT</D:getlastmodified></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
  <D:propstat><D:prop><D:displayname/></D:prop><D:status>HTTP/1.1 404 Not Found</D:status></D:propstat></D:response>
 <D:response><D:href>http://nas:8080/dav/Movies/Season%201/</D:href>
  <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype><D:getlastmodified>Wed, 14 Oct 2026 10:00:00 GMT</D:getlastmodified></D:prop>
  <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
 <D:response><D:href>/dav/Movies/.hidden.mkv</D:href>
  <D:propstat><D:prop><D:resourcetype/><D:getcontentlength>5</D:getcontentlength></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
 <D:response><D:href>/dav/Movies/Thumbs.db</D:href>
  <D:propstat><D:prop><D:resourcetype/><Z:Win32FileAttributes>00000002</Z:Win32FileAttributes><D:ishidden>1</D:ishidden></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
</D:multistatus>"""

    @Test fun parsesEntries() {
        val files = PropfindParser.parse(xml, "dav", "Movies").associateBy { it.name }
        assertEquals(setOf("Брат 1997.mkv", "Season 1", ".hidden.mkv", "Thumbs.db"), files.keys)
        val brat = files.getValue("Брат 1997.mkv")
        assertEquals("Movies/Брат 1997.mkv", brat.path); assertFalse(brat.isDirectory); assertEquals(16_095_208L, brat.size)
        assertEquals(1792076040000L, brat.modified)
        assertTrue(files.getValue("Season 1").isDirectory); assertEquals("Movies/Season 1", files.getValue("Season 1").path)
        assertTrue(files.getValue(".hidden.mkv").hidden); assertTrue(files.getValue("Thumbs.db").hidden)
        assertFalse(brat.hidden)
    }

    @Test fun rootListing() {
        val x = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
<d:response><d:href>/a.mp4</d:href><d:propstat><d:prop><d:getcontentlength>10</d:getcontentlength><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
        assertEquals(listOf("a.mp4"), PropfindParser.parse(x, "", "").map { it.path })
    }
}

class AutoindexParserTest {
    private val nginx = """<html><head><title>Index of /video/</title></head><body><h1>Index of /video/</h1><hr><pre><a href="../">../</a>
<a href="%D0%A1%D0%B5%D1%80%D0%B8%D0%B0%D0%BB%D1%8B/">Сериалы/</a>                                          14-Oct-2026 10:00                   -
<a href="Big%20Movie.mkv">Big Movie.mkv</a>                                      15-Oct-2026 23:54           194406492
<a href="Test%20Clip.mkv">Test Clip.mkv</a>                                      15-Oct-2026 23:54            16095208
<a href="Test%20Clip.srt">Test Clip.srt</a>                                      15-Oct-2026 23:54                 120
</pre><hr></body></html>"""

    private val apache = """<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 3.2 Final//EN"><html><head><title>Index of /media</title></head><body>
<h1>Index of /media</h1><table><tr><th valign="top"><img src="/icons/blank.gif" alt="[ICO]"></th><th><a href="?C=N;O=D">Name</a></th><th><a href="?C=M;O=A">Last modified</a></th><th><a href="?C=S;O=A">Size</a></th></tr>
<tr><td valign="top"><img src="/icons/back.gif" alt="[PARENTDIR]"></td><td><a href="/">Parent Directory</a></td><td>&nbsp;</td><td align="right">  - </td></tr>
<tr><td valign="top"><img src="/icons/folder.gif" alt="[DIR]"></td><td><a href="Anime/">Anime/</a></td><td align="right">2026-10-14 10:00  </td><td align="right">  - </td></tr>
<tr><td valign="top"><img src="/icons/movie.gif" alt="[VID]"></td><td><a href="%D0%91%D1%80%D0%B0%D1%82.mkv">Брат.mkv</a></td><td align="right">2026-10-15 23:54  </td><td align="right"> 15M</td></tr>
<tr><td valign="top"><img src="/icons/movie.gif" alt="[VID]"></td><td><a href="Big.mkv">Big.mkv</a></td><td align="right">2026-10-15 23:54  </td><td align="right">1.2G</td></tr>
<tr><td><a href="http://example.com/x.mkv">ext</a></td></tr>
</table></body></html>"""

    @Test fun nginxListing() {
        val f = AutoindexParser.parse(nginx, "video").associateBy { it.name }
        assertEquals(setOf("Сериалы", "Big Movie.mkv", "Test Clip.mkv", "Test Clip.srt"), f.keys)
        assertTrue(f.getValue("Сериалы").isDirectory); assertEquals("video/Сериалы", f.getValue("Сериалы").path)
        assertEquals(194_406_492L, f.getValue("Big Movie.mkv").size)
        assertTrue(f.getValue("Test Clip.mkv").modified > 0)
    }

    @Test fun apacheListing() {
        val f = AutoindexParser.parse(apache, "").associateBy { it.name }
        assertEquals(setOf("Anime", "Брат.mkv", "Big.mkv"), f.keys)
        assertEquals(15L shl 20, f.getValue("Брат.mkv").size)
        assertEquals((1.2 * (1L shl 30)).toLong(), f.getValue("Big.mkv").size)
        assertTrue(f.getValue("Anime").isDirectory)
    }
}

class DigestAuthTest {
    @Test fun rfc2617Example() {
        val ch = DigestAuth.parseChallenge("""Digest realm="testrealm@host.com", qop="auth,auth-int", nonce="dcd98b7102dd2f0e8b11d0f600bfb0c093", opaque="5ccc069c403ebaf9f0171e9517f40e41"""")
        val h = DigestAuth.authorization(ch, "Mufasa", "Circle Of Life", "GET", "/dir/index.html", 1, "0a4f113b")
        assertTrue(h, h.contains("response=\"6629fae49393a05397450978507c4ef1\""))
        assertTrue(h.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"")); assertTrue(h.contains("qop=auth,"))
    }
}

class WebDavMockServerTest {
    private fun server(url: String) = ServerConfig("mock-" + url.hashCode(), "m", ServerType.WEBDAV, url, username = "u", password = "п")

    @Test fun basicAuthPropfindAndErrors() = runBlocking {
        val ws = MockWebServer()
        ws.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Basic realm=\"x\""))
        ws.enqueue(MockResponse().setResponseCode(207).setBody("""<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/a.mkv</d:href><d:propstat><d:prop><d:getcontentlength>3</d:getcontentlength><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""))
        ws.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Basic realm=\"x\""))
        ws.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Basic realm=\"x\""))
        ws.start()
        val s = server(ws.url("/dav/").toString())
        assertEquals(listOf("a.mkv"), WebDavRepository().list(s, "").map { it.name })
        ws.takeRequest(); val authed = ws.takeRequest()
        assertEquals("PROPFIND", authed.method); assertEquals("1", authed.getHeader("Depth"))
        assertTrue(authed.getHeader("Authorization")!!.startsWith("Basic "))
        try { WebDavRepository().list(s, "x"); fail() } catch (e: SmbError) { assertEquals(SmbErrorKind.AUTH, e.kind) }
        ws.shutdown()
    }

    @Test fun digestAuth() = runBlocking {
        val ws = MockWebServer()
        ws.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Digest realm=\"r\", nonce=\"n\", qop=\"auth\""))
        ws.enqueue(MockResponse().setResponseCode(207).setBody("""<d:multistatus xmlns:d="DAV:"/>"""))
        ws.start()
        WebDavRepository().list(server(ws.url("/").toString()), "Фильмы")
        ws.takeRequest(); val r = ws.takeRequest()
        assertTrue(r.getHeader("Authorization")!!.startsWith("Digest username=\"u\""))
        assertEquals("/%D0%A4%D0%B8%D0%BB%D1%8C%D0%BC%D1%8B/", r.path)
        ws.shutdown()
    }

    @Test fun notFoundMapsToRussian() = runBlocking {
        val ws = MockWebServer(); ws.enqueue(MockResponse().setResponseCode(404)); ws.start()
        try { WebDavRepository().list(server(ws.url("/").toString()), ""); fail() }
        catch (e: SmbError) { assertEquals(SmbErrorKind.NOT_FOUND, e.kind); assertTrue(e.message!!.startsWith("Папка")) }
        ws.shutdown()
    }
}
