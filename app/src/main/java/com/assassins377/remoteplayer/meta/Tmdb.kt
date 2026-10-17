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
import kotlinx.coroutines.launch
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
        overridesFile = File(ctx.filesDir, "tmdb_overrides.json").also { f ->
            if (f.exists()) runCatching { overrides.loadJson(f.readText()) }
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

    // ---- Manual overrides (separate file; NOT removed by clearCache) ----
    private val overrides = OverrideStore()
    private var overridesFile: File? = null
    private val _overridesVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val overridesVersion: kotlinx.coroutines.flow.StateFlow<Int> = _overridesVersion

    fun override(fileKey: String): TmdbOverride? = overrides.get(fileKey)

    fun setOverride(fileKey: String, o: TmdbOverride?) {
        overrides.set(fileKey, o)
        _overridesVersion.value++
        val f = overridesFile ?: return
        val json = overrides.toJson()
        bgScope.launch { runCatching { f.writeText(json) } }
    }

    private val bgScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** Manual search for the correction dialog. Throws on network/auth errors. */
    suspend fun search(query: String, year: Int?, tv: Boolean): List<TmdbMeta> {
        val s = SettingsStore.current
        if (s.tmdbKeyEnc.isBlank()) throw IOException(com.assassins377.remoteplayer.AppStrings.get(com.assassins377.remoteplayer.R.string.err_tmdb_no_key, "Ключ TMDB не задан"))
        return withContext(Dispatchers.IO) {
            TmdbClient(decryptKey(s.tmdbKeyEnc), language(s.language)).searchAll(query, year, tv)
        }
    }

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

    private val detailsCache = HashMap<String, TmdbDetails>()

    /** Full details (overview, genres, cast, trailer); in-memory cache per session. Throws on errors. */
    suspend fun details(id: Int, tv: Boolean): TmdbDetails? {
        val s = SettingsStore.current
        if (s.tmdbKeyEnc.isBlank()) return null
        val k = "${language(s.language)}|$tv|$id"
        synchronized(detailsCache) { detailsCache[k] }?.let { return it }
        val d = withContext(Dispatchers.IO) { TmdbClient(decryptKey(s.tmdbKeyEnc), language(s.language)).details(id, tv) }
        synchronized(detailsCache) { detailsCache[k] = d }
        return d
    }

    /** Called when key/toggle changes. */
    fun resetAuth() { authFailed = false }

    fun clearCache() { cache.clear(); synchronized(detailsCache) { detailsCache.clear() }; file?.delete() }

    /** Media type for details: a manual choice wins over the parsed type. */
    fun isTv(override: com.assassins377.remoteplayer.meta.TmdbOverride?, parsedSeries: Boolean) =
        if (override?.meta != null) override.tv else parsedSeries

    private fun persist() { val f = file ?: return; runCatching { f.writeText(cache.toJson()) } }
}
