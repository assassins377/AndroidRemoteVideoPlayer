package com.assassins377.remoteplayer.library

import com.assassins377.remoteplayer.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in: indexes all three local test servers together, e.g.
 * SMB_IT_HOST=127.0.0.1 SMB_IT_USER=tester SMB_IT_PASS=secret123 SMB_IT_SHARE=media \
 * WEBDAV_IT_URL=http://127.0.0.1:8081/dav/ WEBDAV_IT_USER=tester WEBDAV_IT_PASS=secret123 \
 * HTTP_IT_URL=http://127.0.0.1:8082/video/ ./gradlew testDebugUnitTest
 */
class LibraryIntegrationTest {
    @Test fun combinedLibraryAcrossSmbWebDavHttp() = runBlocking {
        val smb = System.getenv("SMB_IT_HOST"); val dav = System.getenv("WEBDAV_IT_URL"); val http = System.getenv("HTTP_IT_URL")
        assumeTrue(smb != null && dav != null && http != null)
        val servers = listOf(
            ServerConfig("lib-smb", "SMB", ServerType.SMB, smb!!, System.getenv("SMB_IT_SHARE") ?: "media",
                System.getenv("SMB_IT_USER") ?: "", System.getenv("SMB_IT_PASS") ?: ""),
            ServerConfig("lib-dav", "DAV", ServerType.WEBDAV, dav!!, username = System.getenv("WEBDAV_IT_USER") ?: "",
                password = System.getenv("WEBDAV_IT_PASS") ?: ""),
            ServerConfig("lib-http", "HTTP", ServerType.HTTP, http!!),
        )
        val repos = mapOf(ServerType.SMB to SmbRepository(), ServerType.WEBDAV to WebDavRepository(), ServerType.HTTP to HttpRepository())
        LibraryStore.refreshNow(servers) { repos.getValue(it.type) }
        val data = LibraryStore.state.value.data
        assertTrue("errors=${data.errors}", data.errors.isEmpty())
        for (s in servers) {
            val mine = data.entries.filter { it.serverId == s.id }
            assertTrue("${s.name}: $mine", mine.any { it.path == "Movies/Test Clip.mkv" })
            assertTrue("${s.name}: $mine", mine.none { it.name.endsWith(".srt") })
        }
        val found = LibraryQuery.apply(data.entries, "test clip", null, LibrarySort.TITLE, servers.map { it.id }.toSet())
        assertEquals(setOf("lib-smb", "lib-dav", "lib-http"), found.map { it.serverId }.toSet())
        assertEquals(1, LibraryQuery.apply(data.entries, "test clip", setOf("lib-dav"), LibrarySort.TITLE, servers.map { it.id }.toSet()).size)
        SmbPool.invalidate("lib-smb")
    }
}
