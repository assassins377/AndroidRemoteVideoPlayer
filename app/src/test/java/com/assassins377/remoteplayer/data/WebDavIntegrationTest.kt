package com.assassins377.remoteplayer.data

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in: runs only with env vars set, e.g.
 * WEBDAV_IT_URL=http://127.0.0.1:8081/dav/ WEBDAV_IT_USER=tester WEBDAV_IT_PASS=secret123 \
 * HTTP_IT_URL=http://127.0.0.1:8082/video/ ./gradlew testDebugUnitTest
 * Expects Movies/Test Clip.mkv (+ Test Clip.srt) and Фильмы/Брат 1997.mkv.
 */
class WebDavIntegrationTest {
    private val dav = System.getenv("WEBDAV_IT_URL")
    private val http = System.getenv("HTTP_IT_URL")
    private fun davServer(pass: String = System.getenv("WEBDAV_IT_PASS") ?: "") =
        ServerConfig("dav-it-$pass", "dav", ServerType.WEBDAV, dav ?: "", username = System.getenv("WEBDAV_IT_USER") ?: "", password = pass)

    private fun rangeRead(s: ServerConfig, path: String, from: Long, len: Int): Pair<Int, ByteArray> {
        val req = Request.Builder().url(UrlPaths.join(s.host, path)).header("Range", "bytes=$from-${from + len - 1}").build()
        return HttpClients.forServer(s).newCall(req).execute().use { it.code to it.body!!.bytes() }
    }

    @Test fun webdavListRangeAndCyrillic() = runBlocking {
        assumeTrue(dav != null)
        val s = davServer(); val repo = WebDavRepository()
        repo.testConnection(s)
        val root = repo.list(s, "")
        assertTrue(root.toString(), root.any { it.isDirectory && it.name == "Movies" } && root.any { it.name == "Фильмы" })
        val movies = repo.list(s, "Movies")
        val clip = movies.first { it.name == "Test Clip.mkv" }
        assertTrue(clip.size > 1_000_000 && clip.modified > 0)
        assertTrue(movies.any { it.name == "Test Clip.srt" && it.isSubtitle })
        assertEquals("Фильмы/Брат 1997.mkv", repo.list(s, "Фильмы").single().path)
        val (code, head) = rangeRead(s, "Фильмы/Брат 1997.mkv", 0, 4)
        assertEquals(206, code)
        assertEquals(listOf(0x1A, 0x45, 0xDF, 0xA3), head.map { it.toInt() and 0xFF })
        val (code2, tail) = rangeRead(s, clip.path, clip.size - 10, 10)
        assertEquals(206, code2); assertEquals(10, tail.size)
    }

    @Test fun webdavWrongPassword() = runBlocking {
        assumeTrue(dav != null)
        try { WebDavRepository().list(davServer("wrong"), ""); fail() }
        catch (e: SmbError) { assertEquals(SmbErrorKind.AUTH, e.kind); assertTrue(e.message!!.startsWith("Неверный логин или пароль")) }
    }

    @Test fun nginxAutoindex() = runBlocking {
        assumeTrue(http != null)
        val s = ServerConfig("http-it", "http", ServerType.HTTP, http!!)
        val repo = HttpRepository()
        repo.testConnection(s)
        assertTrue(repo.list(s, "").map { it.name }.containsAll(listOf("Movies", "Фильмы")))
        val movies = repo.list(s, "Movies")
        val clip = movies.first { it.name == "Test Clip.mkv" }
        assertEquals("Movies/Test Clip.mkv", clip.path); assertTrue(clip.size > 1_000_000)
        assertEquals(listOf("Брат 1997.mkv"), repo.list(s, "Фильмы").map { it.name })
        assertEquals(206, rangeRead(s, clip.path, 100, 50).first)
    }
}
