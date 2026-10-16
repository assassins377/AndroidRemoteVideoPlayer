package com.assassins377.remoteplayer.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import java.util.Locale

data class TrackOption(val groupIndex: Int, val trackIndex: Int, val label: String, val selected: Boolean)

/** Audio and subtitle tracks of the current item, labelled for the selection dialog. */
data class TrackOptions(val audio: List<TrackOption> = emptyList(), val text: List<TrackOption> = emptyList()) {
    val textOff: Boolean get() = text.none { it.selected }

    companion object {
        fun from(tracks: Tracks): TrackOptions {
            val audio = ArrayList<TrackOption>(); val text = ArrayList<TrackOption>()
            tracks.groups.forEachIndexed { gi, g ->
                val target = when (g.type) { C.TRACK_TYPE_AUDIO -> audio; C.TRACK_TYPE_TEXT -> text; else -> return@forEachIndexed }
                for (ti in 0 until g.length) {
                    if (!g.isTrackSupported(ti)) continue
                    target += TrackOption(gi, ti, label(g.getTrackFormat(ti), target.size + 1), g.isTrackSelected(ti))
                }
            }
            return TrackOptions(audio, text)
        }

        /** "Русский · Дубляж · 5.1" / "English · SRT" / "Дорожка 2". */
        fun label(f: Format, n: Int): String = labelOf(f.label, f.language, f.channelCount, f.sampleMimeType, n)

        fun labelOf(label: String?, language: String?, channels: Int, mime: String?, n: Int): String {
            val parts = ArrayList<String>()
            language?.takeIf { it.isNotBlank() && it != "und" }?.let { tag ->
                val loc = Locale.forLanguageTag(tag)
                parts += loc.getDisplayLanguage(Locale("ru")).replaceFirstChar { it.titlecase(Locale("ru")) }.ifBlank { tag }
            }
            label?.takeIf { it.isNotBlank() && parts.none { p -> p.equals(it, true) } }?.let { parts += it }
            when {
                channels in 1..2 -> parts += if (channels == 1) "моно" else "стерео"
                channels == 6 -> parts += "5.1"
                channels == 8 -> parts += "7.1"
            }
            when (mime) {
                "application/x-subrip" -> parts += "SRT"
                "text/x-ssa" -> parts += "ASS"
                "text/vtt" -> parts += "VTT"
                "application/pgs" -> parts += "PGS"
            }
            return if (parts.isEmpty()) "Дорожка $n" else parts.joinToString(" · ")
        }
    }
}
