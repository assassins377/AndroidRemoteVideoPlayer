package com.assassins377.remoteplayer.core

import com.assassins377.remoteplayer.settings.AppSettings
import java.io.File
import java.util.Properties

/** Current settings for shared code; each platform installs its own provider (Android: DataStore, desktop: JSON file). */
object CoreSettings {
    @Volatile var provider: () -> AppSettings = { AppSettings() }
    val current: AppSettings get() = provider()
}

/**
 * Localized strings for shared code, looked up by resource key (Android: string resources, desktop: bundled
 * properties). Falls back to the given Russian text.
 */
object CoreStrings {
    @Volatile var lookup: (key: String) -> String? = { null }
    fun get(key: String, fallback: String, vararg args: Any): String {
        val s = runCatching { lookup(key) }.getOrNull() ?: fallback
        return if (args.isEmpty()) s else s.format(*args)
    }
    fun sizeUnits(): List<String> = get("size_units", "Б,КБ,МБ,ГБ,ТБ").split(',')
}

/** Tiny key-value store (Android: SharedPreferences, desktop: properties file). */
interface KeyValue {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getLong(key: String, default: Long = 0) = getString(key)?.toLongOrNull() ?: default
    fun putLong(key: String, value: Long) = putString(key, value.toString())
}

/** In-memory [KeyValue] (tests). */
class MemoryKeyValue : KeyValue {
    private val m = HashMap<String, String>()
    @Synchronized override fun getString(key: String) = m[key]
    @Synchronized override fun putString(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
}

/** Properties-file [KeyValue] with atomic writes. */
class FileKeyValue(private val file: File) : KeyValue {
    private val props = Properties().apply { if (file.exists()) runCatching { file.inputStream().use { load(it) } } }
    @Synchronized override fun getString(key: String): String? = props.getProperty(key)
    @Synchronized override fun putString(key: String, value: String?) {
        if (value == null) props.remove(key) else props.setProperty(key, value)
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { props.store(it, null) }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }
}
