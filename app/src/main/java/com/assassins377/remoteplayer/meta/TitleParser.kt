package com.assassins377.remoteplayer.meta

import java.util.Locale

/** Result of parsing a release-style filename like "The.Matrix.1999.1080p.BluRay.x264-GRP.mkv". */
data class ParsedTitle(
    val title: String,
    val year: Int? = null,
    val quality: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
) {
    val isSeries: Boolean get() = season != null
    val episodeTag: String? get() = if (season != null) "S%02d".format(season) + (episode?.let { "E%02d".format(it) } ?: "") else null

    /** "Title (Year)" or "Title S01E02". */
    val display: String get() = buildString {
        append(title)
        if (year != null) append(" ($year)")
        episodeTag?.let { append(" ").append(it) }
    }

    /** Cache/search key: normalized title + year + kind. */
    val key: String get() = (if (isSeries) "tv" else "movie") + "|" + title.lowercase(Locale.ROOT) + "|" + (year ?: "")
}

object TitleParser {
    private val EXT = Regex("""\.(mkv|mp4|avi|mov|webm|m4v|ts|wmv|mpg|mpeg|flv|srt|ass|vtt)$""", RegexOption.IGNORE_CASE)
    private val BRACKETS = Regex("""\[[^\]]*]|\{[^}]*}""")
    private val YEAR = Regex("""^\(?((?:19|20)\d{2})\)?$""")
    private val SXE = Regex("""^[Ss](\d{1,2})[ .]?(?:[Ee](\d{1,3}))?(?:-?[Ee]\d{1,3})?$""")
    private val NXN = Regex("""^(\d{1,2})[xх](\d{2,3})$""")
    private val QUALITY = Regex("""^(?:(480|576|720|1080|1440|2160)[pi]|4k|uhd)$""", RegexOption.IGNORE_CASE)
    private val RU_SEASON = Regex("""(\d{1,2})\s*сезон|сезон\s*(\d{1,2})""", RegexOption.IGNORE_CASE)
    private val RU_EPISODE = Regex("""(\d{1,3})\s*серия|серия\s*(\d{1,3})""", RegexOption.IGNORE_CASE)

    /** Tokens that mark the end of the title (source, codec, audio, release tags). */
    private val JUNK = setOf(
        "bluray", "blu-ray", "bdrip", "brrip", "bdremux", "remux", "web-dl", "webdl", "webrip", "web", "hdrip", "dvdrip",
        "dvd5", "dvd9", "hdtv", "hdtvrip", "satrip", "tvrip", "camrip", "ts", "telesync", "x264", "x265", "h264", "h265",
        "h.264", "h.265", "hevc", "avc", "xvid", "divx", "10bit", "8bit", "hdr", "hdr10", "dv", "sdr", "aac", "ac3", "dts",
        "dts-hd", "truehd", "atmos", "ddp5", "dd5", "eac3", "flac", "mp3", "proper", "repack", "extended", "unrated",
        "imax", "internal", "limited", "multi", "dual", "dub", "mvo", "avo", "rus", "eng", "ukr", "sub", "subs", "itunes",
        "amzn", "nf", "hmax", "dsnp", "lostfilm", "newstudio", "rutracker", "complete", "directors", "director's",
    )

    fun parse(fileName: String): ParsedTitle {
        val stem0 = EXT.replace(fileName.trim(), "")
        val stem = BRACKETS.replace(stem0, " ")
            .replace(Regex("""[._]"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        val tokens = stem.split(' ').filter { it.isNotBlank() }
        var year: Int? = null; var quality: String? = null; var season: Int? = null; var episode: Int? = null
        var cut = tokens.size
        // The release year is the LAST year-like token (not first): "Blade Runner 2049 (2017)".
        val yearIdx = tokens.indices.lastOrNull { i -> i > 0 && YEAR.matches(tokens[i].trim('(', ')', '-', ',')) }
        tokens.forEachIndexed { i, raw ->
            val t = raw.trim('(', ')', '-', ',')
            var marker = false
            YEAR.matchEntire(t)?.let {
                // A leading year is part of the title ("2012", "1917").
                if (i == yearIdx) { year = it.groupValues[1].toInt(); marker = true }
            }
            SXE.matchEntire(t)?.let { m -> season = m.groupValues[1].toInt(); episode = m.groupValues[2].toIntOrNull(); marker = true }
            NXN.matchEntire(t)?.let { m -> season = m.groupValues[1].toInt(); episode = m.groupValues[2].toInt(); marker = true }
            QUALITY.matchEntire(t)?.let { m ->
                quality = when (val h = m.groupValues[1]) { "", "2160" -> "4K"; else -> "${h}p" }; marker = true
            }
            if (t.lowercase(Locale.ROOT).removeSuffix("1").removeSuffix(".") in JUNK || t.lowercase(Locale.ROOT) in JUNK) marker = true
            if (marker && i < cut && (i > 0)) cut = i
        }
        // Russian "1 сезон 2 серия" / "Сезон 1 Серия 2"
        RU_SEASON.find(stem)?.let { m ->
            season = season ?: (m.groupValues[1].ifEmpty { m.groupValues[2] }).toInt()
            cut = minOf(cut, stem.substring(0, m.range.first).trim().split(' ').filter { it.isNotBlank() }.size)
        }
        RU_EPISODE.find(stem)?.let { m ->
            episode = episode ?: (m.groupValues[1].ifEmpty { m.groupValues[2] }).toInt()
            if (season == null) season = 1
            cut = minOf(cut, stem.substring(0, m.range.first).trim().split(' ').filter { it.isNotBlank() }.size)
        }
        var title = tokens.take(cut).joinToString(" ")
            .replace(Regex("""\s*[(\[]\s*$"""), "")
            .trim(' ', '-', ',', '(', '–')
        // "Name - Release group" style leftovers
        title = title.replace(Regex("""\s+-\s*$"""), "").trim()
        if (title.isEmpty()) title = stem.ifEmpty { fileName }
        return ParsedTitle(title, year, quality, season, episode)
    }

    /** Human-readable size with auto unit (Б/КБ/МБ/ГБ/ТБ), binary multiples. */
    fun formatSize(bytes: Long, units: List<String> = listOf("Б", "КБ", "МБ", "ГБ", "ТБ")): String {
        if (bytes < 1024) return "$bytes ${units[0]}"
        var v = bytes.toDouble(); var u = 0
        while (v >= 1024 && u < units.lastIndex) { v /= 1024; u++ }
        val num = if (v < 10) String.format(Locale.ROOT, "%.1f", v).replace('.', ',') else v.toLong().toString()
        return "$num ${units[u]}"
    }

    /** Subtitle line under the title: "1080p · 4,2 ГБ". */
    fun subtitleLine(p: ParsedTitle, size: Long): String = listOfNotNull(p.quality, formatSize(size)).joinToString(" · ")
}
