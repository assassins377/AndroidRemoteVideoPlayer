package com.assassins377.remoteplayer.library

import com.assassins377.remoteplayer.library.inMemory

import com.assassins377.remoteplayer.library.init

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.assassins377.remoteplayer.data.*
import com.assassins377.remoteplayer.settings.SettingsStore
import com.assassins377.remoteplayer.ui.TestApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class TreeRepo(val tree: Map<String, List<RemoteFile>>) : RemoteFileRepository {
    override suspend fun list(server: ServerConfig, path: String) = tree[path] ?: throw SmbError(SmbErrorKind.NOT_FOUND, null)
    override fun playbackUri(server: ServerConfig, file: RemoteFile) = "x://${file.path}"
}

/** Fresh in-memory Room DB for [LibraryStore]. */
fun initLibraryForTest(): LibraryDatabase {
    val app = ApplicationProvider.getApplicationContext<Application>()
    SettingsStore.init(app)
    return LibraryDatabase.inMemory(app).also { LibraryStore.init(app, it) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class LibraryTest {
    private val srv = ServerConfig("a", "NAS", ServerType.SMB, "h", "media")
    private lateinit var db: LibraryDatabase
    @Before fun setUp() { db = initLibraryForTest() }

    private val repo = TreeRepo(mapOf(
        "" to listOf(RemoteFile("Movies", "Movies", true), RemoteFile("Брат.1997.mkv", "Брат.1997.mkv", false),
            RemoteFile("notes.txt", "notes.txt", false), RemoteFile(".secret.mkv", ".secret.mkv", false, hidden = true)),
        "Movies" to listOf(RemoteFile("Deep", "Movies/Deep", true), RemoteFile("The.Matrix.1999.1080p.mkv", "Movies/The.Matrix.1999.1080p.mkv", false)),
        "Movies/Deep" to listOf(RemoteFile("Inception.2010.mkv", "Movies/Deep/Inception.2010.mkv", false)),
    ))

    private fun titles(q: String, servers: List<String>? = null, sort: LibrarySort = LibrarySort.TITLE, live: List<String> = listOf("a", "b")) =
        runBlocking { db.dao().query(LibrarySql.list(q, servers, sort, live, 100)).map { it.title } }

    @Test fun scanRecursesWithDepthAndSkipsNonVideoAndHidden() = runBlocking {
        val got = mutableListOf<String>(); var folders = 0
        val n = LibraryScanner.scan(srv, repo, listOf(""), 5, batchSize = 2, onFolder = { f, _ -> folders = f }) { b -> got += b.map { it.path } }
        assertEquals(3, n); assertEquals(3, folders)
        assertEquals(setOf("Брат.1997.mkv", "Movies/The.Matrix.1999.1080p.mkv", "Movies/Deep/Inception.2010.mkv"), got.toSet())
        assertEquals(2, LibraryScanner.scan(srv, repo, listOf(""), 1))
        assertEquals(1, LibraryScanner.scan(srv, repo, listOf(""), 0))
        assertEquals(4, LibraryScanner.scan(srv, repo, listOf(""), 5, includeHidden = true))
    }

    @Test fun rootsParsingAndRootError() = runBlocking {
        assertEquals(listOf(""), LibraryScanner.parseRoots("  "))
        assertEquals(listOf("Movies", "Фильмы/Новые"), LibraryScanner.parseRoots("/Movies/\nФильмы/Новые, Movies"))
        assertEquals(1, LibraryScanner.scan(srv, repo, listOf("Movies/Deep"), 3))
        try { LibraryScanner.scan(srv, repo, listOf("missing"), 3); fail() } catch (_: SmbError) {}
        // One missing root among several is skipped.
        assertEquals(2, LibraryScanner.scan(srv, repo, listOf("missing", "Movies"), 3))
    }

    @Test fun nestedNetworkFailureFailsScan() = runBlocking {
        val flaky = object : RemoteFileRepository {
            override suspend fun list(server: ServerConfig, path: String) = when (path) {
                "" -> listOf(RemoteFile("A", "A", true), RemoteFile("B", "B", true))
                "A" -> throw SmbError(SmbErrorKind.ACCESS_DENIED, null)
                else -> throw SmbError(SmbErrorKind.NETWORK, null)
            }
            override fun playbackUri(server: ServerConfig, file: RemoteFile) = ""
        }
        try { LibraryScanner.scan(srv, flaky, listOf(""), 3); fail() } catch (e: SmbError) { assertEquals(SmbErrorKind.NETWORK, e.kind) }
    }

    @Test fun ftsMatchExpression() {
        assertEquals("брат* 1997*", LibrarySql.match("  Брат.1997 "))
        assertEquals("ежик*", LibrarySql.match("Ёжик\"*"))
        assertNull(LibrarySql.match(" - "))
    }

    @Test fun refreshUpsertsKeepsAddedAtDeletesStaleAndReportsErrors() = runBlocking {
        val bad = ServerConfig("b", "Bad", ServerType.HTTP, "http://x/")
        LibraryStore.refreshNow(listOf(srv, bad)) { if (it.id == "b") TreeRepo(emptyMap()) else repo }
        val st = LibraryStore.state.value
        assertFalse(st.scanning); assertTrue(st.errors.containsKey("b")); assertEquals(3, st.progress["a"]?.files)
        assertEquals(3, db.dao().count())
        val added = db.dao().query(LibrarySql.list("", null, LibrarySort.TITLE, listOf("a"), 10)).associate { it.path to it.addedAt }
        // second scan: Inception removed, new file appears
        val repo2 = TreeRepo(repo.tree + ("Movies/Deep" to listOf(RemoteFile("Avatar.2009.mkv", "Movies/Deep/Avatar.2009.mkv", false))))
        Thread.sleep(5)
        LibraryStore.refreshNow(listOf(srv)) { repo2 }
        val rows = db.dao().query(LibrarySql.list("", null, LibrarySort.TITLE, listOf("a"), 10))
        assertEquals(listOf("Avatar (2009)", "The Matrix (1999)", "Брат (1997)"), rows.map { it.title })
        assertEquals(added["Брат.1997.mkv"], rows.first { it.path == "Брат.1997.mkv" }.addedAt)
    }

    @Test fun failedScanKeepsOldEntries() = runBlocking {
        LibraryStore.refreshNow(listOf(srv)) { repo }
        LibraryStore.refreshNow(listOf(srv)) { TreeRepo(emptyMap()) }
        assertEquals(3, db.dao().count())
    }

    @Test fun sqlSearchFilterSort() = runBlocking {
        val now = 1000L
        db.dao().upsert(listOf(
            LibraryRows.of("a", "Брат.1997.mkv", "Брат.1997.mkv", 0, 0, 3, now),
            LibraryRows.of("b", "The.Matrix.1999.mkv", "The.Matrix.1999.mkv", 0, 0, 1, now),
            LibraryRows.of("b", "Avatar.2009.mkv", "Avatar.2009.mkv", 0, 0, 2, now),
            LibraryRows.of("gone", "Zombie.mkv", "Zombie.mkv", 0, 0, 0, now),
        ))
        assertEquals(listOf("Avatar (2009)", "The Matrix (1999)", "Брат (1997)"), titles(""))
        assertEquals(listOf("Avatar (2009)", "The Matrix (1999)", "Брат (1997)"), titles("", sort = LibrarySort.YEAR))
        assertEquals(listOf("Брат (1997)", "Avatar (2009)", "The Matrix (1999)"), titles("", sort = LibrarySort.ADDED))
        assertEquals(listOf("Avatar (2009)", "The Matrix (1999)"), titles("", listOf("b")))
        assertEquals(listOf("The Matrix (1999)"), titles("matr"))
        assertEquals(listOf("Брат (1997)"), titles("БРАТ"))
        assertEquals(2, db.dao().queryCount(LibrarySql.count("", listOf("b"), listOf("a", "b"))))
    }

    @Test fun legacyJsonImport() = runBlocking {
        val json = """{"entries":[{"serverId":"a","path":"x/Брат.1997.mkv","name":"Брат.1997.mkv","addedAt":42}],"updatedAt":99}"""
        assertEquals(1, LibraryStore.importLegacy(json))
        val r = db.dao().query(LibrarySql.list("брат", null, LibrarySort.TITLE, listOf("a"), 10)).single()
        assertEquals(42L, r.addedAt)
    }

    @Test fun cancelStopsScan() = runBlocking {
        val slow = object : RemoteFileRepository {
            override suspend fun list(server: ServerConfig, path: String): List<RemoteFile> { kotlinx.coroutines.delay(60_000); return emptyList() }
            override fun playbackUri(server: ServerConfig, file: RemoteFile) = ""
        }
        val job = launch(kotlinx.coroutines.Dispatchers.IO) { LibraryStore.refreshNow(listOf(srv)) { slow } }
        kotlinx.coroutines.delay(200); job.cancel(); job.join()
        assertFalse(LibraryStore.state.value.scanning)
    }

    @Test fun timeoutPerServer() = runBlocking {
        val slow = object : RemoteFileRepository {
            override suspend fun list(server: ServerConfig, path: String): List<RemoteFile> { kotlinx.coroutines.delay(60_000); return emptyList() }
            override fun playbackUri(server: ServerConfig, file: RemoteFile) = ""
        }
        val old = LibraryStore.serverTimeoutMs; LibraryStore.serverTimeoutMs = 200
        try { LibraryStore.refreshNow(listOf(srv)) { slow } } finally { LibraryStore.serverTimeoutMs = old }
        assertEquals("timeout", LibraryStore.state.value.errors["a"])
    }
}

