package com.assassins377.remoteplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

/** Parses a WebDAV PROPFIND (Depth: 1) multistatus response. Pure; unit-tested. */
object PropfindParser {
    private val RFC1123 = ThreadLocal.withInitial {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
    }

    /**
     * @param basePath decoded server base path (e.g. "dav" for http://h/dav/)
     * @param listedPath decoded path being listed, relative to base ("" = root)
     */
    fun parse(xml: String, basePath: String, listedPath: String): List<RemoteFile> {
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val doc = f.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val listed = listedPath.trim('/')
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        val out = ArrayList<RemoteFile>()
        for (i in 0 until responses.length) {
            val r = responses.item(i) as Element
            val href = r.text("href") ?: continue
            val rel = UrlPaths.relative(basePath, href) ?: continue
            if (rel.trim('/') == listed) continue // the folder itself
            val prop = okProp(r) ?: continue
            val isDir = prop.getElementsByTagNameNS("DAV:", "collection").length > 0
            val name = rel.substringAfterLast('/').ifEmpty { prop.text("displayname").orEmpty() }
            if (name.isEmpty()) continue
            out += RemoteFile(
                name = name,
                path = rel,
                isDirectory = isDir,
                size = prop.text("getcontentlength")?.trim()?.toLongOrNull() ?: 0,
                modified = prop.text("getlastmodified")?.let { runCatching { RFC1123.get()!!.parse(it.trim())!!.time }.getOrNull() } ?: 0,
                hidden = name.startsWith(".") || prop.text("ishidden")?.trim() in setOf("1", "true"),
            )
        }
        return out
    }

    /** prop of the propstat with a 2xx status (ignores 404 propstat blocks). */
    private fun okProp(r: Element): Element? {
        val stats = r.getElementsByTagNameNS("DAV:", "propstat")
        for (i in 0 until stats.length) {
            val ps = stats.item(i) as Element
            val status = ps.text("status").orEmpty()
            if (status.isEmpty() || Regex(""" 2\d\d """).containsMatchIn("$status ")) {
                return ps.getElementsByTagNameNS("DAV:", "prop").item(0) as? Element
            }
        }
        return null
    }

    private fun Element.text(local: String): String? =
        (getElementsByTagNameNS("DAV:", local).item(0) ?: getElementsByTagNameNS("*", local).item(0))?.textContent
}

/** WebDAV via OkHttp PROPFIND; playback through rvp-http:// links (auth by server id, Range seeking). */
class WebDavRepository : RemoteFileRepository {
    private val body = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:displayname/></d:prop></d:propfind>"""

    override suspend fun list(server: ServerConfig, path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val url = UrlPaths.join(server.host, path, dir = true)
        val req = Request.Builder().url(url).method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1").build()
        try {
            HttpClients.forServer(server).newCall(req).execute().use { r ->
                HttpErrors.check(r)
                if (r.code != 207) throw SmbError(SmbErrorKind.UNKNOWN, java.io.IOException("Не WebDAV-сервер (HTTP ${r.code})"))
                PropfindParser.parse(r.body!!.string(), UrlPaths.hrefPath(server.host), path)
                    .filter { it.isDirectory || it.isVideo || it.isSubtitle }
            }
        } catch (t: Throwable) { throw HttpErrors.wrap(t) }
    }

    override fun playbackUri(server: ServerConfig, file: RemoteFile): String = HttpUri.build(server.id, file.path)

    suspend fun testConnection(server: ServerConfig) { list(server, "") }
}
