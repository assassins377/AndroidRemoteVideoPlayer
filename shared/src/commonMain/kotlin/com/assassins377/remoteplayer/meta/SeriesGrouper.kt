package com.assassins377.remoteplayer.meta

import com.assassins377.remoteplayer.data.RemoteFile

/** One episode of a series folder. */
data class Episode(val file: RemoteFile, val season: Int, val episode: Int?, val parsed: ParsedTitle)

/** Groups video files of a series (any depth already listed) into seasons with ordered episodes. */
object SeriesGrouper {
    private val SEASON_DIR = Regex("""(?iU)\b(?:season|сезон|s)\s*0*(\d{1,2})\b""")

    fun group(files: List<RemoteFile>): Map<Int, List<Episode>> = files.filter { it.isVideo }.map { f ->
        val p = TitleParser.parse(f.name)
        val dirSeason = f.path.trim('/').split('/').dropLast(1).lastOrNull()?.let { SEASON_DIR.find(it)?.groupValues?.get(1)?.toInt() }
        Episode(f, p.season ?: dirSeason ?: 1, p.episode, p)
    }.groupBy { it.season }.toSortedMap().mapValues { (_, eps) -> eps.sortedWith(compareBy({ it.episode ?: Int.MAX_VALUE }, { it.file.name.lowercase() })) }

    /** Episodes flattened in watch order (season, episode). */
    fun playlist(seasons: Map<Int, List<Episode>>): List<RemoteFile> = seasons.values.flatten().map { it.file }

    fun next(playlist: List<RemoteFile>, current: RemoteFile): RemoteFile? =
        playlist.indexOfFirst { it.path == current.path }.takeIf { it >= 0 }?.let { playlist.getOrNull(it + 1) }

    fun looksLikeSeries(files: List<RemoteFile>): Boolean =
        files.count { it.isVideo && TitleParser.parse(it.name).season != null } >= 2 || files.any { it.isDirectory && SEASON_DIR.containsMatchIn(it.name) }
}
