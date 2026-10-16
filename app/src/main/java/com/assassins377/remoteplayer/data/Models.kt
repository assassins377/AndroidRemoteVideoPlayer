package com.assassins377.remoteplayer.data

enum class ServerType { SMB, HTTP, WEBDAV }

data class ServerConfig(
    val id: String,
    val name: String,
    val type: ServerType,
    /** SMB: host; HTTP/WebDAV: base URL */
    val host: String,
    /** SMB share name (SMB only) */
    val share: String = "",
    val username: String = "",
    val password: String = "",
    val domain: String = "",
)

data class RemoteFile(
    val name: String,
    /** Path relative to the share / base URL */
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
) {
    val isVideo: Boolean get() = !isDirectory && VIDEO_EXT.any { name.endsWith(it, ignoreCase = true) }

    companion object {
        val VIDEO_EXT = listOf(".mp4", ".mkv", ".avi", ".mov", ".webm", ".m4v", ".ts", ".wmv")
    }
}
