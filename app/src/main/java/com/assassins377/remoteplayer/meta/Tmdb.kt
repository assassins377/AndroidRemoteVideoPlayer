package com.assassins377.remoteplayer.meta

import android.content.Context
import android.os.Build
import com.assassins377.remoteplayer.data.KeystoreSecretStore
import com.assassins377.remoteplayer.settings.AppLanguage
import com.assassins377.remoteplayer.settings.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

@Serializable
data class TmdbMeta(
    val id: Int,
    val title: String,
    val originalTitle: String = "",
    val year: Int? = null,
    val overview: String = "",
    val posterPath: String? = null,
    val rating: Double = 0.0,
) {
    fun posterUrl(size: String = "w342"): String? = posterPath?.let { "https://image.tmdb.org/t/p/$size$it" }
}

/** Cache entry: [meta] null = "searched, nothing found" (negative cache, shorter TTL). */
@Serializable
data class CacheEntry(val meta: TmdbMeta?, val fetchedAt: Long)

/** In-memory TTL cache with JSON (de)serialization; pure for unit tests. */
class TmdbCache(
    private val hitTtlMs: Long = 30L * 24 * 3600_000,
    private val missTtlMs: Long = 24L * 3600_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val map = HashMap<String, CacheEntry>()

    /** Returns the entry if fresh, else null (caller should fetch). */
    @Synchronized fun get(key: String): CacheEntry? {
        val e = map[key] ?: return null
        val ttl = if (e.meta != null) hitTtlMs else missTtlMs
        return if (now() - e.fetchedAt <= ttl) e else null
    }

    @Synchronized fun put(key: String, meta: TmdbMeta?) { map[key] = CacheEntry(meta, now()) }
    @Synchronized fun clear() = map.clear()
    @Synchronized fun toJson(): String = json.encodeToString(map.toMap())
    @Synchronized fun loadJson(s: String) { map.clear(); map.putAll(json.decodeFromString<Map<String, CacheEntry>>(s)) }

    companion object { private val json = Json { ignoreUnknownKeys = true } }
}

/** TMDB API v3 search. Key: v3 API key (32 hex) or v4 read access token (JWT, "eyJ..."). */
class TmdbClient(private val key: String, private val language: String) {
    fun search(p: ParsedTitle): TmdbMeta? {
        val tv = p.isSeries
        val q = URLEncoder.encode(p.title, "UTF-8")
        var url = "https://api.themoviedb.org/3/search/${if (tv) "tv" else "movie"}?query=$q&language=$language&include_adult=false"
        if (p.year != null) url += if (tv) "&first_air_date_year=${p.year}" else "&year=${p.year}"
        val bearer = isBearer(key)
        if (!bearer) url += "&api_key=" + URLEncoder.encode(key, "UTF-8")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("Accept", "application/json")
            if (bearer) setRequestProperty("Authorization", "Bearer $key")
        }
        try {
            val code = conn.responseCode
            if (code == 401) throw TmdbAuthException()
            if (code !in 200..299) throw IOException("TMDB HTTP $code")
            return parseSearch(conn.inputStream.use { it.readBytes().decodeToString() }, tv)
        } finally { conn.disconnect() }
    }

    companion object {
        fun isBearer(key: String) = key.startsWith("eyJ") || key.length > 40

        /** First result of a /search response, or null. */
        fun parseSearch(body: String, tv: Boolean): TmdbMeta? {
            val r = Json.parseToJsonElement(body).jsonObject["results"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
            val date = r.str(if (tv) "first_air_date" else "release_date").orEmpty()
            return TmdbMeta(
                id = r["id"]?.jsonPrimitive?.intOrNull ?: return null,
                title = r.str(if (tv) "name" else "title").orEmpty(),
                originalTitle = r.str(if (tv) "original_name" else "original_title").orEmpty(),
                year = date.take(4).toIntOrNull(),
                overview = r.str("overview").orEmpty(),
                posterPath = r.str("poster_path"),
                rating = r.str("vote_average")?.toDoubleOrNull() ?: 0.0,
            )
        }
    }
}

class TmdbAuthException : IOException("TMDB: неверный ключ API")

/**
 * Lookup with JSON file cache (TTL), in-flight de-duplication and max 3 parallel requests.
 * Returns null when disabled, no key, offline or nothing found — UI falls back to the cleaned title.
 */
object TmdbRepository {
    private val secrets by lazy { KeystoreSecretStore("rvp_tmdb_key") }
    private val cache = TmdbCache()
    private val limiter = Semaphore(3)
    private val inFlight = HashMap<String, CompletableDeferred<TmdbMeta?>>()
    private var file: File? = null
    private var appContext: Context? = null
    @Volatile private var authFailed = false

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        file = File(ctx.filesDir, "tmdb_cache.json").also { f ->
            if (f.exists()) runCatching { cache.loadJson(f.readText()) }
        }
    }

    fun encryptKey(plain: String): String = if (plain.isBlank()) "" else secrets.encrypt(plain.trim())
    private fun decryptKey(enc: String): String = runCatching { secrets.decrypt(enc) }.getOrDefault("")

    /** TMDB language tag: explicit RU/EN, or the effective app/device locale when SYSTEM. */
    fun language(lang: AppLanguage): String = when (lang) {
        AppLanguage.EN -> "en-US"
        AppLanguage.RU -> "ru-RU"
        AppLanguage.SYSTEM -> {
            val loc = appContext?.resources?.configuration?.let { cfg ->
                if (Build.VERSION.SDK_INT >= 24) cfg.locales[0] else {
                    @Suppress("DEPRECATION") cfg.locale
                }
            } ?: Locale.getDefault()
            if (loc.language.equals("en", ignoreCase = true)) "en-US" else "ru-RU"
        }
    }

    fun cached(p: ParsedTitle): TmdbMeta? = cache.get(keyFor(p))?.meta

    private fun keyFor(p: ParsedTitle) = language(SettingsStore.current.language) + "|" + p.key

    suspend fun lookup(p: ParsedTitle): TmdbMeta? {
        val s = SettingsStore.current
        if (!s.tmdbEnabled || s.tmdbKeyEnc.isBlank() || authFailed) return null
        val key = keyFor(p)
        cache.get(key)?.let { return it.meta }
        val (deferred, owner) = synchronized(inFlight) {
            inFlight[key]?.let { it to false } ?: CompletableDeferred<TmdbMeta?>().also { inFlight[key] = it }.let { it to true }
        }
        if (!owner) return deferred.await()
        try {
            val result = limiter.withPermit {
                withContext(Dispatchers.IO) {
                    val client = TmdbClient(decryptKey(s.tmdbKeyEnc), language(s.language))
                    var meta = client.search(p)
                    // Year in filename can be off by one / missing on TMDB: retry without year.
                    if (meta == null && p.year != null) meta = client.search(p.copy(year = null))
                    cache.put(key, meta)
                    persist()
                    meta
                }
            }
            deferred.complete(result)
            return result
        } catch (e: TmdbAuthException) {
            authFailed = true; deferred.complete(null); return null
        } catch (e: Exception) {
            deferred.complete(null); return null // offline etc.: not cached, retried next time
        } finally {
            synchronized(inFlight) { inFlight.remove(key) }
        }
    }

    /** Called when key/toggle changes. */
    fun resetAuth() { authFailed = false }

    fun clearCache() { cache.clear(); file?.delete() }

    private fun persist() { val f = file ?: return; runCatching { f.writeText(cache.toJson()) } }
}
