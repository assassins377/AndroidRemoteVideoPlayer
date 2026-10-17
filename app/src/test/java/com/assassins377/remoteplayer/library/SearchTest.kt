package com.assassins377.remoteplayer.library

import com.assassins377.remoteplayer.ui.TestApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class SearchTest {
    @Test fun matchSyntax() {
        assertEquals("\"звездные войны\" 1977*", LibrarySql.match("\"Звёздные войны\" 1977"))
        assertEquals("a* b*", LibrarySql.match("a \" b"))
    }

    @Test fun multiWordPhraseAndSuggestions() = runBlocking {
        val db = initLibraryForTest(); val dao = db.dao(); val live = listOf("a")
        fun r(p: String) = LibraryRows.of("a", p, p, 0, 0, 0, 1)
        dao.upsert(listOf(r("Star.Wars.1977.mkv"), r("Wars.of.the.Stars.2001.mkv"), r("Звёздные.войны.Эпизод.5.1980.mkv"),
            r("Breaking.Bad.S01E01.mkv"), r("Breaking.Bad.S01E02.mkv")))
        fun n(q: String) = runBlocking { dao.queryCount(LibrarySql.count(q, null, live)) }
        assertEquals(2, n("star wars")); assertEquals(1, n("\"star wars\"")); assertEquals(1, n("войны 1980")); assertEquals(0, n("войны 1977"))
        assertEquals(listOf("Breaking Bad"), dao.strings(LibrarySql.suggestions("break", live)!!)) // grouped by series
        assertEquals(listOf("Star Wars (1977)", "Wars of the Stars (2001)"), dao.strings(LibrarySql.suggestions("sta", live)!!))
        assertNull(LibrarySql.suggestions("  ", live))
        assertEquals(0, n("\"star war\"")) // quoted = exact words, no prefix
        assertEquals(emptyList<String>(), dao.strings(LibrarySql.suggestions("break", live, mode = LibraryMode.MOVIES)!!))
        assertEquals(emptyList<String>(), dao.strings(LibrarySql.suggestions("break", live, servers = listOf("zzz"))!!))
        db.close()
    }

    @Test fun recent() {
        assertEquals(listOf("b", "a"), RecentSearches.push(listOf("a", "B"), "b"))
        assertEquals(3, RecentSearches.push(listOf("1", "2", "3"), "x", max = 3).size)
    }
}
