package com.assassins377.remoteplayer

import com.assassins377.remoteplayer.data.HttpUri
import com.assassins377.remoteplayer.data.SmbUri
import com.assassins377.remoteplayer.settings.*
import com.assassins377.remoteplayer.ui.home.ContinueItems
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FavoriteListTest {
    private fun fav(s: String, p: String, t: Long = 0) = Favorite(s, p, p.substringAfterLast('/'), p.endsWith("dir"), t)

    @Test fun toggleUniqueByServerAndPath() {
        val l = FavoriteList()
        assertTrue(l.toggle(fav("a", "Movies/x.mkv"))); assertTrue(l.contains("a", "/Movies/x.mkv/"))
        assertFalse(l.contains("b", "Movies/x.mkv"))
        assertFalse(l.toggle(fav("a", "Movies/x.mkv"))); assertTrue(l.all().isEmpty())
    }

    @Test fun orderJsonAndServerRemoval() {
        val l = FavoriteList(); l.toggle(fav("a", "old.mkv", 1)); l.toggle(fav("a", "Shows/dir", 5)); l.toggle(fav("b", "z.mkv", 3))
        assertEquals(listOf("Shows/dir", "z.mkv", "old.mkv"), l.all().map { it.path })
        val r = FavoriteList.fromJson(l.toJson()); assertEquals(l.all(), r.all())
        r.removeServer("a"); assertEquals(listOf("z.mkv"), r.all().map { it.path })
        assertTrue(FavoriteList.fromJson("garbage").all().isEmpty())
    }
}

class FileFilterTest {
    @Test fun matches() {
        assertTrue(FileFilter.matches("The.Matrix.1999.1080p.mkv", "The Matrix (1999)", "matrix"))
        assertTrue(FileFilter.matches("The.Matrix.1999.1080p.mkv", null, "the matrix"))   // dots → spaces
        assertTrue(FileFilter.matches("Ёлки.2010.mkv", "Ёлки (2010)", "елки"))             // ё = е
        assertTrue(FileFilter.matches("Брат.1997.mkv", null, "БРАТ 1997"))
        assertFalse(FileFilter.matches("Брат.1997.mkv", null, "брат 2000"))
        assertTrue(FileFilter.matches("anything", null, "   "))
    }
}

class ResumeRecentTest {
    @Test fun recentOrderProgressAndRemove() {
        var t = 0L
        val r = ResumePositions(now = { t })
        t = 1; r.update("a", 60_000, 600_000)
        t = 3; r.update("b", 30_000, 0)
        t = 2; r.update("c", 300_000, 600_000)
        assertEquals(listOf("b", "c", "a"), r.recent(10).map { it.first })
        assertEquals(0.5f, r.recent(10)[1].second.progress!!, 0.001f); assertNull(r.recent(10)[0].second.progress)
        assertTrue(r.remove("b")); assertEquals(2, r.recent(10).size); assertEquals(1, r.recent(1).size)
    }

    @Test fun decodesOldAndNewFormat() {
        assertEquals(ResumeEntry(12_000), ResumePositions.decode("""{"rvp-smb://s/a.mkv":12000}""")["rvp-smb://s/a.mkv"])
        assertEquals(ResumeEntry(5_000, 9_000, 7), ResumePositions.decode("""{"k":{"positionMs":5000,"durationMs":9000,"updatedAt":7}}""")["k"])
    }

    @Test fun continueItems() {
        val entries = listOf(
            SmbUri.build("s1", "Movies/The.Matrix.1999.1080p.mkv") to ResumeEntry(60_000, 120_000, 2),
            HttpUri.build("gone", "x.mkv") to ResumeEntry(10_000),
            "garbage" to ResumeEntry(10_000),
        )
        val items = ContinueItems.build(entries) { if (it == "s1") "NAS" else null }
        assertEquals(1, items.size)
        assertEquals("The Matrix (1999)", items[0].title); assertEquals("NAS", items[0].serverName); assertEquals(0.5f, items[0].progress!!, 0.001f)
    }
}

class StringResourcesTest {
    private fun names(path: String) = Regex("""<string name="([^"]+)"""").findAll(File(path).readText()).map { it.groupValues[1] }.toSet()

    @Test fun englishCoversAllRussianStrings() {
        val ru = names("src/main/res/values/strings.xml"); val en = names("src/main/res/values-en/strings.xml")
        assertTrue("missing en: ${ru - en}", (ru - en).isEmpty()); assertTrue("extra en: ${en - ru}", (en - ru).isEmpty())
        assertTrue(ru.size > 100)
    }

    /** UI code must use resources: no Cyrillic string literals in ui/ (except native language names). */
    @Test fun noHardcodedCyrillicInUi() {
        val allowed = setOf("Русский")
        val bad = File("src/main/java/com/assassins377/remoteplayer/ui").walk().filter { it.extension == "kt" }.flatMap { f ->
            Regex(""""((?:[^"\\]|\\.)*)"""").findAll(f.readText()).map { it.groupValues[1] }
                .filter { Regex("[А-Яа-яЁё]").containsMatchIn(it) && it !in allowed }
                .map { "${f.name}: $it" }
        }.toList()
        // Russian fallbacks passed to AppStrings.get(...) are allowed (only used when no Context, i.e. tests).
        val realBad = bad.filterNot { b -> fallbacks.any { b.endsWith(it) } }
        assertTrue("hardcoded: $realBad", realBad.isEmpty())
    }

    private val fallbacks by lazy {
        File("src/main/java/com/assassins377/remoteplayer/ui").walk().filter { it.extension == "kt" }.flatMap { f ->
            Regex("""AppStrings\.get\([^,]+,\s*"((?:[^"\\]|\\.)*)"""").findAll(f.readText()).map { it.groupValues[1] }
        }.toSet()
    }
}
