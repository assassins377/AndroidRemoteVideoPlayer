package com.assassins377.remoteplayer

import android.content.Context
import androidx.annotation.StringRes

/**
 * Localized strings for non-Compose code (errors, view models). Falls back to the given Russian text
 * when no context is set (JVM unit tests).
 */
object AppStrings {
    @Volatile var context: Context? = null

    fun get(@StringRes id: Int, fallback: String, vararg args: Any): String {
        val c = context
        val s = if (c != null) runCatching { c.getString(id) }.getOrDefault(fallback) else fallback
        return if (args.isEmpty()) s else s.format(*args)
    }

    fun sizeUnits(): List<String> = get(R.string.size_units, "Б,КБ,МБ,ГБ,ТБ").split(',')
}
