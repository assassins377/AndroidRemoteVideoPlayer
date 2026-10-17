package com.assassins377.remoteplayer.data

import kotlinx.serialization.Serializable

@Serializable
enum class ServerType { SMB, HTTP, WEBDAV, JELLYFIN }

@Serializable
data class ServerConfig(
    val id: String,
    val name: String,
    val type: ServerType,
    /** SMB: host; HTTP/WebDAV: base URL */
    val host: String,
    /** SMB share name (SMB only) */
    val share: String = "",
    val username: String = "",
    /** Never serialized to plain storage; kept encrypted by [SecretStore]. */
    @kotlinx.serialization.Transient val password: String = "",
    val domain: String = "",
    /** HTTPS: SHA-256 of a user-trusted (e.g. self-signed) server certificate; "" = system CAs only. */
    val pinnedCertSha256: String = "",
)

data class RemoteFile(
    val name: String,
    /** Path relative to the share / base URL */
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    /** Last modified, epoch ms (0 = unknown). */
    val modified: Long = 0,
    val hidden: Boolean = false,
) {
    val isSubtitle: Boolean get() = !isDirectory && SUB_EXT.any { name.endsWith(it, ignoreCase = true) }

    val isVideo: Boolean get() = !isDirectory && VIDEO_EXT.any { name.endsWith(it, ignoreCase = true) }

    companion object {
        val VIDEO_EXT = listOf(".mp4", ".mkv", ".avi", ".mov", ".webm", ".m4v", ".ts", ".wmv")
        val SUB_EXT = listOf(".srt", ".ass", ".ssa", ".vtt")
    }
}
