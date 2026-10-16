package com.assassins377.remoteplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale

/** Parses Apache / nginx / lighttpd-style autoindex HTML pages. Pure; unit-tested. */
object AutoindexParser {
    private val LINK = Regex("""<a\s[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>(?=(.{0,200}))""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val DATE_ISO = Regex("""(\d{4}-\d{2}-\d{2} \d{2}:\d{2})""")
    private val DATE_NGINX = Regex("""(\d{2}-[A-Za-z]{3}-\d{4} \d{2}:\d{2})""")
    private val SIZE = Regex("""(?:^|[\s>])(\d+(?:\.\d+)?)\s*([KMGT]i?B?|B)?\s*(?:<|$)""")

    fun parse(html: String, listedPath: String): List<RemoteFile> {
        val base = listedPath.trim('/')
        val seen = HashSet<String>()
        return LINK.findAll(html).mapNotNull { m ->
            val href = m.groupValues[1].trim()
            if (href.startsWith("?") || href.startsWith("#") || href.startsWith("mailto:") || href.startsWith("javascript:")) return@mapNotNull null
            if (href == "../" || href == ".." || href == "/" ) return@mapNotNull null
            if (Regex("^[a-z]+://", RegexOption.IGNORE_CASE).containsMatchIn(href)) return@mapNotNull null // external
            val isDir = href.endsWith("/")
            val clean = href.substringBefore('?').trimEnd('/')
            // Root-relative links: keep only children of the listed folder.
            val name = if (clean.startsWith("/")) {
                val segs = clean.split('/').filter { it.isNotEmpty() }
                if (segs.size < 1) return@mapNotNull null
                val parent = segs.dropLast(1).joinToString("/") { UrlPaths.decodeSegment(it) }
                if (!parent.endsWith(base) ) return@mapNotNull null
                UrlPaths.decodeSegment(segs.last())
            } else {
                if (clean.contains('/')) return@mapNotNull null
                UrlPaths.decodeSegment(clean)
            }
            if (name.isEmpty() || name == "." || name == ".." || name.equals("Parent Directory", true)) return@mapNotNull null
            if (!seen.add(name)) return@mapNotNull null
            val tail = m.groupValues[3].substringBefore("<a ").substringBefore("\n<tr").let { stripTags(it) }
            RemoteFile(
                name = name,
                path = if (base.isEmpty()) name else "$base/$name",
                isDirectory = isDir,
                size = if (isDir) 0 else parseSize(tail),
                modified = parseDate(tail),
                hidden = name.startsWith("."),
            )
        }.toList()
    }

    private fun stripTags(s: String) = s.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()

    fun parseSize(tail: String): Long {
        val afterDate = tail.replace(DATE_ISO, " ").replace(DATE_NGINX, " ").trim()
        val m = Regex("""^(\d+(?:[.,]\d+)?)\s*([KMGT])?""", RegexOption.IGNORE_CASE).find(afterDate) ?: return 0
        val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0
        val mult = when (m.groupValues[2].uppercase()) { "K" -> 1L shl 10; "M" -> 1L shl 20; "G" -> 1L shl 30; "T" -> 1L shl 40; else -> 1L }
        return (n * mult).toLong()
    }

    fun parseDate(tail: String): Long {
        DATE_ISO.find(tail)?.let { return runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(it.value)!!.time }.getOrDefault(0) }
        DATE_NGINX.find(tail)?.let { return runCatching { SimpleDateFormat("dd-MMM-yyyy HH:mm", Locale.US).parse(it.value)!!.time }.getOrDefault(0) }
        return 0
    }
}

/**
 * HTTP source. If the URL points to a video file it plays directly; otherwise the page is parsed as an
 * Apache/nginx autoindex listing. Optional Basic/Digest auth uses the stored (encrypted) credentials.
 */
class HttpRepository : RemoteFileRepository {
    override suspend fun list(server: ServerConfig, path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        if (path.isBlank() && RemoteFile.VIDEO_EXT.any { server.host.substringBefore('?').endsWith(it, true) }) {
            return@withContext listOf(RemoteFile(UrlPaths.decodeSegment(server.host.substringAfterLast('/')), "", false))
        }
        val url = UrlPaths.join(server.host, path, dir = true)
        try {
            HttpClients.forServer(server).newCall(Request.Builder().url(url).build()).execute().use { r ->
                HttpErrors.check(r)
                val type = r.header("Content-Type").orEmpty()
                if (!type.contains("html", true)) throw SmbError(SmbErrorKind.UNKNOWN, java.io.IOException(com.assassins377.remoteplayer.AppStrings.get(com.assassins377.remoteplayer.R.string.err_not_listing, "Не страница со списком файлов (%s)", type)))
                AutoindexParser.parse(r.body!!.string(), path).filter { it.isDirectory || it.isVideo || it.isSubtitle }
            }
        } catch (t: Throwable) { throw HttpErrors.wrap(t) }
    }

    override fun playbackUri(server: ServerConfig, file: RemoteFile): String = HttpUri.build(server.id, file.path)

    /** HEAD on the configured URL (works for a direct video link and for a listing page). */
    suspend fun testConnection(server: ServerConfig) = withContext(Dispatchers.IO) {
        try {
            HttpClients.forServer(server).newCall(Request.Builder().url(server.host).head().build()).execute().use { HttpErrors.check(it) }
        } catch (t: Throwable) { throw HttpErrors.wrap(t) }
    }
}
