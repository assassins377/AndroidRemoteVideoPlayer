package com.assassins377.remoteplayer.data

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Playback link for HTTP/WebDAV files: `rvp-http://<serverId>/<path>`.
 * No URL credentials; HttpRemoteDataSource resolves base URL + auth from ServerStore by id.
 */
object HttpUri {
    const val SCHEME = "rvp-http"

    fun build(serverId: String, path: String): String =
        "$SCHEME://${enc(serverId)}/" + path.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { enc(it) }

    fun parse(uri: String): Pair<String, String>? {
        val u = runCatching { URI(uri) }.getOrNull() ?: return null
        if (u.scheme != SCHEME) return null
        val id = u.rawAuthority?.let(::dec)?.takeIf { it.isNotBlank() } ?: return null
        val path = (u.rawPath ?: "").trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { dec(it) }
        return id to path
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun dec(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
}

/** Uniform view over rvp-smb:// and rvp-http:// links. */
data class PlaybackRef(val serverId: String, val path: String, val http: Boolean) {
    fun withPath(p: String) = if (http) HttpUri.build(serverId, p) else SmbUri.build(serverId, p)

    companion object {
        fun parse(uri: String): PlaybackRef? =
            SmbUri.parse(uri)?.let { PlaybackRef(it.first, it.second, false) }
                ?: HttpUri.parse(uri)?.let { PlaybackRef(it.first, it.second, true) }
    }
}

/** URL helpers: percent-encode path segments (UTF-8, Cyrillic, spaces as %20), keep base as typed. */
object UrlPaths {
    fun encodeSegment(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%7E", "~")

    fun decodeSegment(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    /** base "http://h/dav/" + relative decoded path "Фильмы/My Movie.mkv" → encoded absolute URL. */
    fun join(base: String, path: String, dir: Boolean = false): String {
        val b = base.trimEnd('/')
        val segs = path.trim('/').split('/').filter { it.isNotEmpty() }
        val tail = segs.joinToString("/") { encodeSegment(it) }
        return when {
            tail.isEmpty() -> "$b/"
            dir -> "$b/$tail/"
            else -> "$b/$tail"
        }
    }

    /** Decoded path of an absolute or root-relative href, without trailing slash. */
    fun hrefPath(href: String): String {
        val raw = runCatching { URI(href.replace(" ", "%20")).rawPath }.getOrNull() ?: href
        return raw.split('/').filter { it.isNotEmpty() }.joinToString("/") { decodeSegment(it) }
    }

    /** Path of [childHref] relative to [basePath] (both decoded, slash-trimmed), or null if outside. */
    fun relative(basePath: String, childHref: String): String? {
        val child = hrefPath(childHref)
        val b = basePath.trim('/')
        return when {
            b.isEmpty() -> child
            child == b -> ""
            child.startsWith("$b/") -> child.removePrefix("$b/")
            else -> null
        }
    }
}
