package com.assassins377.remoteplayer.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persisted form: server fields as JSON, password stored separately as encrypted blob. */
@Serializable
internal data class StoredServer(val server: ServerConfig, val secret: String = "")

internal class ServerCodec(private val secrets: SecretStore) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(servers: List<ServerConfig>): String =
        json.encodeToString(servers.map { StoredServer(it, secrets.encrypt(it.password)) })

    fun decode(raw: String?): List<ServerConfig> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<StoredServer>>(raw) }.getOrDefault(emptyList())
            .map { s -> s.server.copy(password = runCatching { secrets.decrypt(s.secret) }.getOrDefault("")) }
    }
}
