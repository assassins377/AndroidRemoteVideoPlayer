package com.assassins377.remoteplayer.library

import com.assassins377.remoteplayer.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LibraryTest {
    private val srv = ServerConfig("a", "NAS", ServerType.SMB, "h", "media")

    private class TreeRepo(val tree: Map<String, List<RemoteFile>>) : RemoteFileRepository {
        override suspend fun list(server: ServerConfig, path: String) = tree[path] ?: throw SmbError(SmbErrorKind.NOT_FOUND, null)
        override fun playbackUri(server: ServerConfig, file: RemoteFile) = "x://${file.path}"
    }

    private val repo = TreeRepo(mapOf(
        "" to listOf(RemoteFile("Movies", "Movies", true), RemoteFile("Brat.1997.mkv", "Brat.1997.mkv", false),
            RemoteFile("notes.txt", "notes.txt", false), RemoteFile(".secret.mkv", ".secret.mkv", false, hidden = true)),
        "Movies" to listOf(RemoteFile("Deep", "Movies/Deep", true), RemoteFile("The.Matrix.1999.1080p.mkv", "Movies/The.Matrix.1999.1080p.mkv", false)),
        "Movies/Deep" to listOf(RemoteFile("Inception.2010.mkv", "Movies/Deep/Inception.2010.mkv", false)),
    ))

    @Test fun scanRecursesWithDepthAndSkipsNonVideoAndHidden() = runBlocking {
        val all = LibraryScanner.scan(srv, repo, listOf(""), 5).map { it.path }.toSet()
        assertEquals(setOf("Brat.1997.mkv", "Movies/The.Matrix.1999.1080p.mkv", "Movies/Deep/Inception.2010.mkv"), all)
        assertEquals(2, LibraryScanner.scan(srv, repo, listOf(""), 1).size)
        assertEquals(1, LibraryScanner.scan(srv, repo, listOf(""), 0).size)
        assertTrue(LibraryScanner.scan(srv, repo, listOf(""), 5, includeHidden = true).any { it.name == ".secret.mkv" })
    }

    @Test fun rootsParsingAndRootError() = runBlocking {
        assertEquals(listOf(""), LibraryScanner.parseRoots("  "))
        assertEquals(listOf("Movies", "Фильмы/Новые"), LibraryScanner.parseRoots("/Movies/\nФильмы/Новые, Movies"))
        assertEquals(1, LibraryScanner.scan(srv, repo, listOf("Movies/Deep"), 3).size)
        try { LibraryScanner.scan(srv, repo, listOf("missing"), 3); fail() } catch (_: SmbError) {}
    }

    @Test fun mergeKeepsAddedAt() {
        val prev = listOf(LibraryEntry("a", "x.mkv", "x.mkv", addedAt = 5), LibraryEntry("b", "x.mkv", "x.mkv", addedAt = 7))
        val m = LibraryScanner.merge("a", listOf(RemoteFile("x.mkv", "x.mkv", false), RemoteFile("y.mkv", "y.mkv", false)), prev, 100)
        assertEquals(listOf(5L, 100L), m.map { it.addedAt })
    }

    @Test fun queryFilterSortSearch() {
        val e = listOf(
            LibraryEntry("a", "Brat.1997.mkv", "Brat.1997.mkv", addedAt = 3),
            LibraryEntry("b", "The.Matrix.1999.mkv", "The.Matrix.1999.mkv", addedAt = 1),
            LibraryEntry("b", "Avatar.2009.mkv", "Avatar.2009.mkv", addedAt = 2),
            LibraryEntry("gone", "Zombie.mkv", "Zombie.mkv"),
        )
        val live = setOf("a", "b")
        fun t(l: List<LibraryEntry>) = l.map { it.name.substringBefore('.') }
        assertEquals(listOf("Avatar", "Brat", "The"), t(LibraryQuery.apply(e, "", null, LibrarySort.TITLE, live)))
        assertEquals(listOf("Avatar", "The", "Brat"), t(LibraryQuery.apply(e, "", null, LibrarySort.YEAR, live)))
        assertEquals(listOf("Brat", "Avatar", "The"), t(LibraryQuery.apply(e, "", null, LibrarySort.ADDED, live)))
        assertEquals(listOf("Avatar", "The"), t(LibraryQuery.apply(e, "", setOf("b"), LibrarySort.TITLE, live)))
        assertEquals(listOf("The"), t(LibraryQuery.apply(e, "matrix", null, LibrarySort.TITLE, live)))
    }

    @Test fun jsonRoundTrip() {
        val d = LibraryData(listOf(LibraryEntry("a", "p/x.mkv", "x.mkv", 10, 20, 30)), 99, mapOf("b" to "err"))
        assertEquals(d, LibraryStore.decode(LibraryStore.encode(d)))
    }

    @Test fun refreshCombinesServersAndKeepsOldOnError() = runBlocking {
        val bad = ServerConfig("bad", "Bad", ServerType.HTTP, "http://x/")
        LibraryStore.refreshNow(listOf(srv, bad)) { if (it.id == "bad") TreeRepo(emptyMap()) else repo }
        val st = LibraryStore.state.value
        assertFalse(st.scanning)
        assertEquals(3, st.data.entries.size)
        assertTrue(st.data.errors.containsKey("bad"))
    }
}
