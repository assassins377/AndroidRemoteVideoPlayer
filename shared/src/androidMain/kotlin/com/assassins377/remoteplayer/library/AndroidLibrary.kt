package com.assassins377.remoteplayer.library

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import com.assassins377.remoteplayer.core.KeyValue
import com.assassins377.remoteplayer.settings.WatchedStore

/** Same file name/location as before ("library.db" in the app's databases dir), framework SQLite. */
fun LibraryDatabase.Companion.open(ctx: Context): LibraryDatabase =
    Room.databaseBuilder<LibraryDatabase>(ctx.applicationContext, "library.db").fallbackToDestructiveMigration(true).build()

fun LibraryDatabase.Companion.inMemory(ctx: Context): LibraryDatabase =
    Room.inMemoryDatabaseBuilder<LibraryDatabase>(ctx.applicationContext).build()

/** Android setup: SharedPreferences "library_meta" (same keys as before) and filesDir for the legacy JSON import. */
fun LibraryStore.init(ctx: Context, database: LibraryDatabase = LibraryDatabase.open(ctx)) =
    init(database, PrefsKeyValue(ctx.getSharedPreferences("library_meta", Context.MODE_PRIVATE)), ctx.filesDir)

fun WatchedStore.init(ctx: Context) = init(ctx.filesDir)

class PrefsKeyValue(private val p: SharedPreferences) : KeyValue {
    override fun getString(key: String): String? = runCatching { p.getString(key, null) }.getOrNull()
        ?: runCatching { p.getLong(key, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.toString() }.getOrNull()
    override fun putString(key: String, value: String?) { p.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
    override fun getLong(key: String, default: Long): Long =
        runCatching { p.getLong(key, default) }.getOrNull() ?: getString(key)?.toLongOrNull() ?: default
    override fun putLong(key: String, value: Long) { p.edit().putLong(key, value).apply() }
}
