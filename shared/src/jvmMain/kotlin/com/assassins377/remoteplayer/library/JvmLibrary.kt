package com.assassins377.remoteplayer.library

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import java.io.File

/** Desktop: bundled SQLite (FTS4 included) in [file]. */
fun LibraryDatabase.Companion.open(file: File): LibraryDatabase {
    file.parentFile?.mkdirs()
    return Room.databaseBuilder<LibraryDatabase>(name = file.absolutePath)
        .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigration(true).build()
}

fun LibraryDatabase.Companion.inMemory(): LibraryDatabase =
    Room.inMemoryDatabaseBuilder<LibraryDatabase>().setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
