package com.assassins377.remoteplayer.ui.player

import android.util.Rational

/** Pure helpers for gestures/PiP (unit-tested). */
object PlayerMath {
    /** Horizontal swipe → seek delta: full screen width = [fullWidthMs] (default 90 s), clamped to [0, duration]. */
    fun swipeTarget(startMs: Long, dragPx: Float, widthPx: Float, durationMs: Long, fullWidthMs: Long = 90_000): Long {
        if (widthPx <= 0f) return startMs
        val t = startMs + (dragPx / widthPx * fullWidthMs).toLong()
        return if (durationMs > 0) t.coerceIn(0, durationMs) else t.coerceAtLeast(0)
    }

    fun formatTime(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** "+0:15" / "−1:02" */
    fun formatDelta(ms: Long): String = (if (ms < 0) "−" else "+") + formatTime(kotlin.math.abs(ms))

    /** PiP requires aspect ratio within [1:2.39, 2.39:1]; returns (num, den) clamped, default 16:9. */
    fun pipAspect(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 16 to 9
        val r = width.toDouble() / height
        return when {
            r > 2.39 -> 239 to 100
            r < 1 / 2.39 -> 100 to 239
            else -> width to height
        }
    }

    fun rational(p: Pair<Int, Int>) = Rational(p.first, p.second)

    /** "+0,5 с" / "−1,2 с" / "0 с". */
    fun formatDelay(ms: Long): String {
        val sign = if (ms > 0) "+" else if (ms < 0) "−" else ""
        val v = kotlin.math.abs(ms)
        val txt = if (v % 1000 == 0L) "${v / 1000}" else String.format(java.util.Locale.ROOT, "%.1f", v / 1000.0).replace('.', ',')
        return "$sign$txt " + com.assassins377.remoteplayer.AppStrings.get(com.assassins377.remoteplayer.R.string.sec_unit, "с")
    }
}
