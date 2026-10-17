package com.assassins377.remoteplayer.meta

import com.assassins377.remoteplayer.settings.AppLanguage
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

@Serializable
data class TmdbCast(val name: String, val character: String = "", val profilePath: String? = null) {
    fun photoUrl(size: String = "w185"): String? = profilePath?.let { "https://image.tmdb.org/t/p/$size$it" }
}

@Serializable
data class TmdbDetails(
    val overview: String = "", val rating: Double = 0.0, val genres: List<String> = emptyList(), val runtimeMin: Int? = null,
    val tagline: String = "", val cast: List<TmdbCast> = emptyList(), val trailerYoutubeKey: String? = null, val seasons: Int? = null,
) {
    val trailerUrl: String? get() = trailerYoutubeKey?.let { "https://www.youtube.com/watch?v=$it" }
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
    fun search(p: ParsedTitle): TmdbMeta? = searchAll(p.title, p.year, p.isSeries).firstOrNull()

    fun searchAll(title: String, year: Int?, tv: Boolean): List<TmdbMeta> {
        val q = URLEncoder.encode(title, "UTF-8")
        var url = "https://api.themoviedb.org/3/search/${if (tv) "tv" else "movie"}?query=$q&language=$language&include_adult=false"
        if (year != null) url += if (tv) "&first_air_date_year=$year" else "&year=$year"
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
            return parseSearchAll(conn.inputStream.use { it.readBytes().decodeToString() }, tv)
        } finally { conn.disconnect() }
    }

    /** /movie|tv/{id} with credits + videos. */
    fun details(id: Int, tv: Boolean): TmdbDetails {
        var url = "https://api.themoviedb.org/3/${if (tv) "tv" else "movie"}/$id?language=$language&append_to_response=credits,videos&include_video_language=${language.take(2)},en"
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
            return parseDetails(conn.inputStream.use { it.readBytes().decodeToString() }, tv)
        } finally { conn.disconnect() }
    }

    companion object {
        fun isBearer(key: String) = key.startsWith("eyJ") || key.length > 40

        fun parseDetails(body: String, tv: Boolean): TmdbDetails {
            val o = Json.parseToJsonElement(body).jsonObject
            fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
            val runtime = if (tv) o["episode_run_time"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.intOrNull else o["runtime"]?.jsonPrimitive?.intOrNull
            val cast = o["credits"]?.jsonObject?.get("cast")?.jsonArray.orEmpty().take(12).mapNotNull { c ->
                val co = c.jsonObject; val name = co.str("name") ?: return@mapNotNull null
                TmdbCast(name, co.str("character").orEmpty(), co.str("profile_path"))
            }
            val videos = o["videos"]?.jsonObject?.get("results")?.jsonArray.orEmpty().map { it.jsonObject }
                .filter { it.str("site") == "YouTube" && it.str("type") in setOf("Trailer", "Teaser") }
            val trailer = (videos.firstOrNull { it.str("type") == "Trailer" } ?: videos.firstOrNull())?.str("key")
            return TmdbDetails(
                overview = o.str("overview").orEmpty(),
                rating = o.str("vote_average")?.toDoubleOrNull() ?: 0.0,
                genres = o["genres"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject.str("name") },
                runtimeMin = runtime,
                tagline = o.str("tagline").orEmpty(),
                cast = cast,
                trailerYoutubeKey = trailer,
                seasons = if (tv) o["number_of_seasons"]?.jsonPrimitive?.intOrNull else null,
            )
        }

        /** First result of a /search response, or null. */
        fun parseSearch(body: String, tv: Boolean): TmdbMeta? = parseSearchAll(body, tv).firstOrNull()

        fun parseSearchAll(body: String, tv: Boolean): List<TmdbMeta> =
            Json.parseToJsonElement(body).jsonObject["results"]?.jsonArray.orEmpty().mapNotNull { parseResult(it.jsonObject, tv) }

        private fun parseResult(r: JsonObject, tv: Boolean): TmdbMeta? {
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

class TmdbAuthException : IOException(com.assassins377.remoteplayer.core.CoreStrings.get("err_tmdb_key", "TMDB: неверный ключ API"))
