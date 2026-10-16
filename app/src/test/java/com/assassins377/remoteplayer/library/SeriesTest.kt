package com.assassins377.remoteplayer.library

import com.assassins377.remoteplayer.settings.WatchedStore
import com.assassins377.remoteplayer.ui.TestApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class SeriesTest {
    @Test fun detectEpisodes() {
        assertEquals(EpisodeInfo("Breaking Bad", 1, 2), Episodes.detect("TV/Breaking.Bad.S01E02.720p.mkv", "Breaking.Bad.S01E02.720p.mkv"))
        assertEquals(EpisodeInfo("The Office", 2, 3), Episodes.detect("The.Office.2x03.mkv", "The.Office.2x03.mkv"))
        assertEquals(EpisodeInfo("Слово пацана", 1, 4), Episodes.detect("Слово пацана/Сезон 1/Серия 04.mkv", "Серия 04.mkv"))
        assertEquals(EpisodeInfo("Lost", 3, 5), Episodes.detect("Lost (2004)/Season 3/E05.mkv", "E05.mkv"))
        assertEquals(EpisodeInfo("Dark", 1, 1), Episodes.detect("Dark/S01E01.mkv", "S01E01.mkv"))
        assertNull(Episodes.detect("Movies/The.Matrix.1999.mkv", "The.Matrix.1999.mkv"))
        assertEquals(Episodes.key("Breaking Bad"), Episodes.key("breaking.bad"))
    }

    @Test fun groupingAndNext() = runBlocking {
        val db = initLibraryForTest(); val dao = db.dao()
        fun r(p: String) = LibraryRows.of("a", p, p.substringAfterLast('/'), 0, 0, 0, 1)
        dao.upsert(listOf(r("BB/Breaking.Bad.S01E02.mkv"), r("BB/Breaking.Bad.S01E01.mkv"), r("BB/Breaking.Bad.S02E01.mkv"),
            r("Lost/Season 1/E01.mkv"), r("Movies/Avatar.2009.mkv")))
        val series = dao.series(LibrarySql.series("", null, LibrarySort.TITLE, listOf("a"), 50))
        assertEquals(listOf("Breaking Bad" to 3, "Lost" to 1), series.map { it.seriesTitle to it.episodes })
        assertEquals(2, series[0].seasons)
        assertEquals(1, dao.queryCount(LibrarySql.count("", null, listOf("a"), LibraryMode.MOVIES)))
        val eps = dao.episodes(Episodes.key("Breaking Bad"), listOf("a"))
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1), eps.map { it.season to it.episode })
        assertEquals("BB/Breaking.Bad.S02E01.mkv", Episodes.next(eps, eps[1])?.path)
        assertNull(Episodes.next(eps, eps[2]))
        // search by series title finds episodes even when file has no title
        assertEquals(1, dao.queryCount(LibrarySql.count("lost", null, listOf("a"))))
        db.close()
    }

    @Test fun watchedThreshold() {
        assertTrue(WatchedStore.shouldMark(91_000, 100_000)); assertFalse(WatchedStore.shouldMark(50_000, 100_000)); assertFalse(WatchedStore.shouldMark(5, 0))
        val k = WatchedStore.key("a", "/x.mkv"); WatchedStore.set(k, true); assertTrue(WatchedStore.isWatched("a/x.mkv")); WatchedStore.toggle(k); assertFalse(WatchedStore.isWatched(k))
    }
}
