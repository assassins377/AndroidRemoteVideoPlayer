package com.assassins377.remoteplayer.settings

enum class DecoderMode { AUTO, HARDWARE, SOFTWARE }
enum class SmbDialect { AUTO, SMB2, SMB3 }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class SortOrder { NAME, DATE, SIZE }
enum class AppLanguage(val tag: String) { SYSTEM(""), RU("ru"), EN("en") }

/** All user settings. Keys are the property names; see [AppSettings.fromMap]. */
data class AppSettings(
    // Playback
    val defaultSpeed: Float = 1f,
    val seekStepSec: Int = 10,
    val resume: Boolean = true,
    val autoplayNext: Boolean = true,
    val audioLanguage: String = "",
    val decoder: DecoderMode = DecoderMode.AUTO,
    // Subtitles
    val subtitleLanguage: String = "",
    val autoLoadSubtitles: Boolean = true,
    val subtitleSizeSp: Int = 20,
    val subtitleColor: Long = 0xFFFFFFFF,
    val subtitleBackground: Long = 0x80000000,
    val subtitleOffsetMs: Int = 0,
    // Network
    val bufferMaxSec: Int = 120,
    val readAheadKb: Int = 1024,
    val timeoutSec: Int = 20,
    val retryCount: Int = 3,
    val smbDialect: SmbDialect = SmbDialect.AUTO,
    val smbEncryption: Boolean = false,
    // UI
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val amoled: Boolean = false,
    val sort: SortOrder = SortOrder.NAME,
    val showHidden: Boolean = false,
    val grid: Boolean = false,
    val language: AppLanguage = AppLanguage.SYSTEM,
    // Controls
    val swipeGestures: Boolean = true,
    val doubleTapSeek: Boolean = true,
    val pipOnMinimize: Boolean = false,
    val lockRotation: Boolean = false,
) {
    val seekStepMs: Long get() = seekStepSec * 1000L
    val readAheadBytes: Int get() = readAheadKb * 1024

    companion object {
        val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val SEEK_STEPS = listOf(10, 15, 30)
        val READ_AHEAD_KB = listOf(256, 512, 1024, 2048, 4096)
        val BUFFER_SEC = listOf(30, 60, 120, 300)
        val SUB_SIZES = listOf(14, 16, 20, 24, 28, 34)

        /** Builds settings from raw stored values; unknown/invalid values fall back to defaults. */
        fun fromMap(m: Map<String, Any?>): AppSettings {
            val d = AppSettings()
            fun b(k: String, def: Boolean) = m[k] as? Boolean ?: def
            fun i(k: String, def: Int, ok: (Int) -> Boolean = { true }) = (m[k] as? Number)?.toInt()?.takeIf(ok) ?: def
            fun l(k: String, def: Long) = (m[k] as? Number)?.toLong() ?: def
            fun s(k: String, def: String) = m[k] as? String ?: def
            fun <E : Enum<E>> e(k: String, def: E, values: Array<E>) = values.firstOrNull { it.name == m[k] } ?: def
            return AppSettings(
                defaultSpeed = (m["defaultSpeed"] as? Number)?.toFloat()?.takeIf { it in SPEEDS } ?: d.defaultSpeed,
                seekStepSec = i("seekStepSec", d.seekStepSec) { it in SEEK_STEPS },
                resume = b("resume", d.resume),
                autoplayNext = b("autoplayNext", d.autoplayNext),
                audioLanguage = s("audioLanguage", d.audioLanguage),
                decoder = e("decoder", d.decoder, DecoderMode.values()),
                subtitleLanguage = s("subtitleLanguage", d.subtitleLanguage),
                autoLoadSubtitles = b("autoLoadSubtitles", d.autoLoadSubtitles),
                subtitleSizeSp = i("subtitleSizeSp", d.subtitleSizeSp) { it in 8..72 },
                subtitleColor = l("subtitleColor", d.subtitleColor),
                subtitleBackground = l("subtitleBackground", d.subtitleBackground),
                subtitleOffsetMs = i("subtitleOffsetMs", d.subtitleOffsetMs) { it in -60_000..60_000 },
                bufferMaxSec = i("bufferMaxSec", d.bufferMaxSec) { it in 15..600 },
                readAheadKb = i("readAheadKb", d.readAheadKb) { it in 64..16_384 },
                timeoutSec = i("timeoutSec", d.timeoutSec) { it in 3..120 },
                retryCount = i("retryCount", d.retryCount) { it in 1..10 },
                smbDialect = e("smbDialect", d.smbDialect, SmbDialect.values()),
                smbEncryption = b("smbEncryption", d.smbEncryption),
                theme = e("theme", d.theme, ThemeMode.values()),
                amoled = b("amoled", d.amoled),
                sort = e("sort", d.sort, SortOrder.values()),
                showHidden = b("showHidden", d.showHidden),
                grid = b("grid", d.grid),
                language = e("language", d.language, AppLanguage.values()),
                swipeGestures = b("swipeGestures", d.swipeGestures),
                doubleTapSeek = b("doubleTapSeek", d.doubleTapSeek),
                pipOnMinimize = b("pipOnMinimize", d.pipOnMinimize),
                lockRotation = b("lockRotation", d.lockRotation),
            )
        }
    }
}
