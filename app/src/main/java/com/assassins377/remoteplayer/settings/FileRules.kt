package com.assassins377.remoteplayer.settings

import androidx.media3.common.MimeTypes
import com.assassins377.remoteplayer.data.RemoteFile

object FileSorter {
    /** Folders first, then by [order] (date/size descending: newest/largest first), name as tiebreak. */
    fun sort(files: List<RemoteFile>, order: SortOrder, showHidden: Boolean): List<RemoteFile> {
        val byName = compareBy<RemoteFile> { it.name.lowercase() }
        val cmp = when (order) {
            SortOrder.NAME -> byName
            SortOrder.DATE -> compareByDescending<RemoteFile> { it.modified }.then(byName)
            SortOrder.SIZE -> compareByDescending<RemoteFile> { if (it.isDirectory) 0 else it.size }.then(byName)
        }
        return files.filter { (showHidden || !it.hidden) && !it.isSubtitle }
            .sortedWith(compareBy<RemoteFile> { !it.isDirectory }.then(cmp))
    }

    /** Next video after [currentName] in name order, or null. */
    fun nextVideo(files: List<RemoteFile>, currentName: String): RemoteFile? {
        val videos = files.filter { it.isVideo }.sortedBy { it.name.lowercase() }
        val i = videos.indexOfFirst { it.name == currentName }
        return if (i >= 0) videos.getOrNull(i + 1) else null
    }
}

data class SubtitleMatch(val file: RemoteFile, val mimeType: String, val language: String?)

object SubtitleMatcher {
    /**
     * Sidecar subtitles with the same base name: "Movie.srt", "Movie.ru.srt", "Movie.eng.forced.ass".
     * Language is the first 2–3 letter tag after the base name.
     */
    fun find(videoName: String, files: List<RemoteFile>): List<SubtitleMatch> {
        val base = videoName.substringBeforeLast('.').lowercase()
        return files.filter { it.isSubtitle }.mapNotNull { f ->
            val stem = f.name.substringBeforeLast('.')
            if (!stem.lowercase().let { it == base || it.startsWith("$base.") }) return@mapNotNull null
            val tags = stem.drop(base.length).split('.').filter { it.isNotEmpty() }
            val lang = tags.firstOrNull { it.length in 2..3 && it.all(Char::isLetter) }?.lowercase()
            SubtitleMatch(f, mimeFor(f.name), lang)
        }
    }

    fun mimeFor(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "vtt" -> MimeTypes.TEXT_VTT
        else -> MimeTypes.TEXT_UNKNOWN
    }
}
