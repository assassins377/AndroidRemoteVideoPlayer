package com.assassins377.remoteplayer.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** In-memory server list. TODO: persist with DataStore/Room, passwords via EncryptedSharedPreferences/Keystore. */
object ServerStore {
    private val _servers = MutableStateFlow<List<ServerConfig>>(emptyList())
    val servers: StateFlow<List<ServerConfig>> = _servers.asStateFlow()

    fun add(server: ServerConfig) = _servers.update { it + server }
    fun remove(id: String) = _servers.update { list -> list.filterNot { it.id == id } }
    fun get(id: String): ServerConfig? = _servers.value.firstOrNull { it.id == id }

    fun repositoryFor(type: ServerType): RemoteFileRepository = when (type) {
        ServerType.SMB -> SmbRepository()
        ServerType.HTTP -> HttpRepository()
        ServerType.WEBDAV -> WebDavRepository()
    }
}
