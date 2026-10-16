package com.assassins377.remoteplayer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "servers")
private val KEY_SERVERS = stringPreferencesKey("servers_json")

/**
 * Server list persisted in DataStore (JSON), passwords encrypted with Android Keystore.
 * Exposes an in-memory StateFlow cache so playback can resolve credentials by id synchronously.
 */
object ServerStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _servers = MutableStateFlow<List<ServerConfig>>(emptyList())
    val servers: StateFlow<List<ServerConfig>> = _servers.asStateFlow()
    private lateinit var context: Context
    private lateinit var codec: ServerCodec

    fun init(ctx: Context, secrets: SecretStore) {
        context = ctx.applicationContext
        codec = ServerCodec(secrets)
        val flow = context.dataStore.data.map { codec.decode(it[KEY_SERVERS]) }
        // Load once synchronously so deep links / process restore into the player work immediately.
        _servers.value = runBlocking { flow.first() }
        scope.launch { flow.collect { _servers.value = it } }
    }

    suspend fun upsert(server: ServerConfig) = save { list ->
        if (list.any { it.id == server.id }) list.map { if (it.id == server.id) server else it } else list + server
    }

    suspend fun remove(id: String) {
        save { list -> list.filterNot { it.id == id } }
        SmbPool.invalidate(id)
        com.assassins377.remoteplayer.settings.FavoritesStore.removeServer(id)
    }

    fun get(id: String): ServerConfig? = _servers.value.firstOrNull { it.id == id }

    private suspend fun save(transform: (List<ServerConfig>) -> List<ServerConfig>) {
        context.dataStore.edit { prefs ->
            val updated = transform(codec.decode(prefs[KEY_SERVERS]))
            prefs[KEY_SERVERS] = codec.encode(updated)
            _servers.value = updated
        }
    }

    /** Imports servers from [ServerTransfer] JSON; existing ids are replaced (password kept if the server already exists). */
    suspend fun importJson(json: String): Int {
        val incoming = ServerTransfer.decode(json)
        save { list ->
            val byId = list.associateBy { it.id }.toMutableMap()
            incoming.forEach { s -> byId[s.id] = s.copy(password = byId[s.id]?.password ?: "") }
            byId.values.toList()
        }
        return incoming.size
    }

    fun exportJson(): String = ServerTransfer.encode(_servers.value)

    fun repositoryFor(type: ServerType): RemoteFileRepository = when (type) {
        ServerType.SMB -> SmbRepository()
        ServerType.HTTP -> HttpRepository()
        ServerType.WEBDAV -> WebDavRepository()
    }
}
