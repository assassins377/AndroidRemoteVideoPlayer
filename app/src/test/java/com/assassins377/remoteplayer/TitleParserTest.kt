package com.assassins377.remoteplayer

import com.assassins377.remoteplayer.meta.TitleParser
import org.junit.Assert.assertEquals
import org.junit.Test

class TitleParserTest {
    private fun check(name: String, title: String, year: Int?, quality: String? = null, s: Int? = null, e: Int? = null) {
        val p = TitleParser.parse(name)
        assertEquals("title of $name", title, p.title)
        assertEquals("year of $name", year, p.year)
        assertEquals("quality of $name", quality, p.quality)
        assertEquals("season of $name", s, p.season)
        assertEquals("episode of $name", e, p.episode)
    }

    @Test fun movies() {
        check("The.Matrix.1999.1080p.BluRay.x264-GRP.mkv", "The Matrix", 1999, "1080p")
        check("Inception (2010) 720p BRRip.mp4", "Inception", 2010, "720p")
        check("Dune_Part_Two_2024_2160p_WEB-DL_HDR10_HEVC.mkv", "Dune Part Two", 2024, "4K")
        check("Blade Runner 2049 (2017) [4K] UHD.mkv", "Blade Runner 2049", 2017, "4K")
        check("1917.2019.BDRip.1080p.mkv", "1917", 2019, "1080p")
        check("2012.mkv", "2012", null)
        check("Avatar.mkv", "Avatar", null)
        check("Movie Without Year HDRip XviD.avi", "Movie Without Year", null)
        check("[RG] Spirited.Away.2001.BDRemux.1080p.mkv", "Spirited Away", 2001, "1080p")
    }

    @Test fun cyrillic() {
        check("Брат.1997.BDRip.720p.mkv", "Брат", 1997, "720p")
        check("Ирония_судьбы_или_С_лёгким_паром_1975_WEB-DL_1080p.mkv", "Ирония судьбы или С лёгким паром", 1975, "1080p")
        check("Москва слезам не верит (1979) DVDRip.avi", "Москва слезам не верит", 1979)
        check("Слово.пацана.S01E03.2023.WEB-DL.1080p.mkv", "Слово пацана", 2023, "1080p", 1, 3)
        check("Метод 2 сезон 5 серия.mp4", "Метод", null, null, 2, 5)
    }

    @Test fun series() {
        check("Breaking.Bad.S05E14.720p.HDTV.x264.mkv", "Breaking Bad", null, "720p", 5, 14)
        check("the.office.us.s02e01.mkv", "the office us", null, null, 2, 1)
        check("Friends 3x07 DVDRip.avi", "Friends", null, null, 3, 7)
        check("Game.of.Thrones.S08.2160p.UHD.mkv", "Game of Thrones", null, "4K", 8, null)
    }

    @Test fun displayStrings() {
        assertEquals("The Matrix (1999)", TitleParser.parse("The.Matrix.1999.1080p.mkv").display)
        assertEquals("Breaking Bad S05E14", TitleParser.parse("Breaking.Bad.S05E14.mkv").display)
        assertEquals("1080p · 4,2 ГБ", TitleParser.subtitleLine(TitleParser.parse("X.2000.1080p.mkv"), 4_500_000_000))
    }

    @Test fun sizes() {
        assertEquals("512 Б", TitleParser.formatSize(512))
        assertEquals("1,0 КБ", TitleParser.formatSize(1024))
        assertEquals("15 МБ", TitleParser.formatSize(16_095_208))
        assertEquals("185 МБ", TitleParser.formatSize(194_406_492))
        assertEquals("1,5 ГБ", TitleParser.formatSize(1_610_612_736))
        assertEquals("0 Б", TitleParser.formatSize(0))
    }
}
