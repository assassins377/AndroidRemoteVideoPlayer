package com.assassins377.remoteplayer.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.assassins377.remoteplayer.meta.ParsedTitle
import com.assassins377.remoteplayer.meta.TmdbCache
import com.assassins377.remoteplayer.meta.TmdbClient
import com.assassins377.remoteplayer.meta.TmdbMeta
import com.assassins377.remoteplayer.settings.AtomicFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** TMDB lookups with the shared client + TTL cache (file), and poster images (disk + memory cache). */
object Tmdb {
    private val cache = TmdbCache()
    private var cacheFile: File? = null
    private var posterDir: File? = null
    private val limiter = Semaphore(3)
    private val images = ConcurrentHashMap<String, ImageBitmap>()
    private val http by lazy { OkHttpClient() }

    fun init(cacheFile: File, posterDir: File) {
        this.cacheFile = cacheFile; this.posterDir = posterDir.apply { mkdirs() }
        if (cacheFile.exists()) runCatching { cache.loadJson(cacheFile.readText()) }
    }

    private fun key(): String = Env.secrets.get("tmdb_key").orEmpty()
    fun setKey(k: String) = Env.secrets.put("tmdb_key", k.trim())
    fun hasKey() = key().isNotBlank()

    fun cached(p: ParsedTitle): TmdbMeta? = cache.get(Strings.lang + "|" + p.key)?.meta

    suspend fun lookup(p: ParsedTitle): TmdbMeta? {
        if (!Env.settings.current.tmdbEnabled || !hasKey()) return null
        val k = Strings.lang + "|" + p.key
        cache.get(k)?.let { return it.meta }
        return limiter.withPermit {
            withContext(Dispatchers.IO) {
                runCatching { TmdbClient(key(), if (Strings.lang == "ru") "ru-RU" else "en-US").search(p) }
                    .onSuccess { cache.put(k, it); cacheFile?.let { f -> runCatching { AtomicFiles.write(f, cache.toJson()) } } }
                    .getOrNull()
            }
        }
    }

    suspend fun poster(url: String): ImageBitmap? {
        images[url]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val f = File(posterDir, sha1(url) + ".jpg")
                val bytes = if (f.exists()) f.readBytes() else http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@runCatching null
                    r.body!!.bytes().also { f.writeBytes(it) }
                }
                org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap().also { images[url] = it }
            }.getOrNull()
        }
    }

    fun clear() { cache.clear(); cacheFile?.delete(); posterDir?.listFiles()?.forEach { it.delete() }; images.clear() }
    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
