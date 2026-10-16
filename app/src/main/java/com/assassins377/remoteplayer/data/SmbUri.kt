package com.assassins377.remoteplayer.data

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Playback link for SMB files: `rvp-smb://<serverId>/<path>`.
 * No host/share/credentials in the link; SmbDataSource resolves them from ServerStore by id.
 */
object SmbUri {
    const val SCHEME = "rvp-smb"

    fun build(serverId: String, path: String): String =
        "$SCHEME://${enc(serverId)}/" + path.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { enc(it) }

    /** Returns (serverId, path with '/' separators) or null if not an rvp-smb link. */
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
