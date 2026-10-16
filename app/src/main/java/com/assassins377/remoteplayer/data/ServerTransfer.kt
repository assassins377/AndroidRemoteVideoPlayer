package com.assassins377.remoteplayer.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Export/import format for the server list. Passwords are never included ([ServerConfig.password] is @Transient). */
object ServerTransfer {
    @Serializable
    data class File(val format: String = "remoteplayer-servers", val version: Int = 1, val servers: List<ServerConfig>)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun encode(servers: List<ServerConfig>): String = json.encodeToString(File(servers = servers))

    fun decode(text: String): List<ServerConfig> {
        val f = json.decodeFromString<File>(text)
        require(f.format == "remoteplayer-servers") { "Unknown file format" }
        return f.servers.filter { it.id.isNotBlank() && it.host.isNotBlank() }
    }
}
