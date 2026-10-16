package com.assassins377.remoteplayer.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File

/** Shifts cue times of SRT / WebVTT / ASS-SSA text by [ms] (negative = earlier, clamped at 0). Pure. */
object SubtitleShift {
    private val SRT_VTT = Regex("""(?:(\d{1,2}):)?(\d{2}):(\d{2})([,.])(\d{3})(?=\s*-->|\s*$|\s+(?:align|line|position|size|vertical))""", RegexOption.MULTILINE)
    private val ARROW_LINE = Regex("""^.*-->.*$""", RegexOption.MULTILINE)
    private val ASS_LINE = Regex("""^(Dialogue:\s*[^,]*,)(\d+):(\d{2}):(\d{2})\.(\d{2}),(\d+):(\d{2}):(\d{2})\.(\d{2}),""", RegexOption.MULTILINE)

    fun shift(text: String, ms: Long, ext: String): String {
        if (ms == 0L) return text
        return if (ext.lowercase() in setOf("ass", "ssa")) shiftAss(text, ms) else shiftTimed(text, ms)
    }

    private fun shiftTimed(text: String, ms: Long) = ARROW_LINE.replace(text) { line ->
        SRT_VTT.replace(line.value) { m ->
            val hasH = m.groupValues[1].isNotEmpty()
            val t = (m.groupValues[1].ifEmpty { "0" }.toLong() * 3600 + m.groupValues[2].toLong() * 60 + m.groupValues[3].toLong()) * 1000 + m.groupValues[5].toLong()
            val v = (t + ms).coerceAtLeast(0)
            val sep = m.groupValues[4]
            val h = v / 3_600_000; val mi = v / 60_000 % 60; val s = v / 1000 % 60; val f = v % 1000
            if (hasH || h > 0) "%02d:%02d:%02d%s%03d".format(h, mi, s, sep, f) else "%02d:%02d%s%03d".format(mi, s, sep, f)
        }
    }

    private fun shiftAss(text: String, ms: Long) = ASS_LINE.replace(text) { m ->
        val g = m.groupValues
        fun t(i: Int) = ((g[i].toLong() * 3600 + g[i + 1].toLong() * 60 + g[i + 2].toLong()) * 100 + g[i + 3].toLong()) * 10
        fun f(v0: Long): String { val v = (v0 + ms).coerceAtLeast(0) / 10; return "%d:%02d:%02d.%02d".format(v / 360_000, v / 6000 % 60, v / 100 % 60, v % 100) }
        "${g[1]}${f(t(2))},${f(t(6))},"
    }
}

/** Per-file subtitle delay (ms), keyed by playback uri; persisted to filesDir/sub_delays.json. */
object SubtitleDelayStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var map = HashMap<String, Long>()
    private var file: File? = null

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "sub_delays.json").also { f ->
            if (f.exists()) runCatching { map = HashMap(Json.decodeFromString<Map<String, Long>>(f.readText())) }
        }
    }

    @Synchronized fun get(uri: String): Long? = map[uri]
    @Synchronized fun set(uri: String, ms: Long?) {
        if (ms == null) map.remove(uri) else map[uri] = ms
        while (map.size > 1000) map.remove(map.keys.first())
        val f = file ?: return; val json = Json.encodeToString(map.toMap())
        scope.launch { runCatching { f.writeText(json) } }
    }
}

/** Reads a (small) remote file fully via the app's routing data source (SMB / HTTP / WebDAV). */
@OptIn(UnstableApi::class)
object RemoteBytes {
    fun readAll(ctx: Context, uri: String, max: Int = 8 * 1024 * 1024): ByteArray {
        val ds = RoutingDataSourceFactory(DefaultDataSource.Factory(ctx)).createDataSource()
        try {
            ds.open(DataSpec(android.net.Uri.parse(uri)))
            val out = ByteArrayOutputStream(); val buf = ByteArray(64 * 1024)
            while (true) { val n = ds.read(buf, 0, buf.size); if (n < 0) break; out.write(buf, 0, n); if (out.size() > max) throw java.io.IOException("too large") }
            return out.toByteArray()
        } finally { runCatching { ds.close() } }
    }
}
