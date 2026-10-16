package com.assassins377.remoteplayer.settings

import java.io.File

/** Write-to-temp then rename, so readers never see a half-written file. */
object AtomicFiles {
    fun write(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) { target.writeText(text); tmp.delete() } }
    }
}
