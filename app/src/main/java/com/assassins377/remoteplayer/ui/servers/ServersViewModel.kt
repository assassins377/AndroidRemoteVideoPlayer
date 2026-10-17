package com.assassins377.remoteplayer.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assassins377.remoteplayer.data.ServerConfig
import com.assassins377.remoteplayer.data.ServerStore
import com.assassins377.remoteplayer.data.ServerType
import com.assassins377.remoteplayer.data.SmbRepository
import com.assassins377.remoteplayer.data.WebDavRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data object Ok : TestState
    data class Failed(val message: String, val cert: com.assassins377.remoteplayer.data.CertInfo? = null) : TestState
}

sealed interface SharesState {
    data object Idle : SharesState
    data object Loading : SharesState
    data class Loaded(val names: List<String>) : SharesState
    data class Failed(val message: String) : SharesState
}

class ServersViewModel : ViewModel() {
    val servers: StateFlow<List<ServerConfig>> = ServerStore.servers

    private val _test = MutableStateFlow<TestState>(TestState.Idle)
    val test: StateFlow<TestState> = _test.asStateFlow()
    private var testJob: Job? = null

    private val _shares = MutableStateFlow<SharesState>(SharesState.Idle)
    val shares: StateFlow<SharesState> = _shares.asStateFlow()

    /** Lists SMB shares (needs host + credentials, share may be blank). Falls back to manual entry on failure. */
    fun loadShares(server: ServerConfig) {
        _shares.value = SharesState.Loading
        viewModelScope.launch {
            _shares.value = runCatching { withTimeout(20_000) { SmbRepository().listShares(server.copy(share = "")) } }
                .fold({ SharesState.Loaded(it) }, { SharesState.Failed(it.message ?: it.toString()) })
        }
    }

    fun get(id: String?): ServerConfig? = id?.let(ServerStore::get)

    fun save(server: ServerConfig, onSaved: () -> Unit) = viewModelScope.launch {
        ServerStore.upsert(if (server.id.isBlank()) server.copy(id = UUID.randomUUID().toString()) else server)
        onSaved()
    }

    fun remove(id: String) = viewModelScope.launch { ServerStore.remove(id) }

    fun resetTest() { testJob?.cancel(); _test.value = TestState.Idle; _shares.value = SharesState.Idle }

    fun testConnection(server: ServerConfig) {
        testJob?.cancel()
        _test.value = TestState.Running
        testJob = viewModelScope.launch {
            _test.value = runCatching {
                withTimeout(15_000) {
                    when (server.type) {
                        ServerType.SMB -> SmbRepository().testConnection(server.copy(id = server.id.ifBlank { "__test__" }))
                        ServerType.HTTP -> com.assassins377.remoteplayer.data.HttpRepository().testConnection(server.copy(id = server.id.ifBlank { "__test__" }))
                        ServerType.WEBDAV -> WebDavRepository().testConnection(server.copy(id = server.id.ifBlank { "__test__" }))
                        ServerType.JELLYFIN -> com.assassins377.remoteplayer.data.JellyfinRepository().testConnection(server.copy(id = server.id.ifBlank { "__test__" }))
                    }
                }
            }.fold({ TestState.Ok }, { TestState.Failed(it.message ?: it.toString(), com.assassins377.remoteplayer.data.CertInfo.from(it)) })
        }
    }

    private suspend fun httpHead(url: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            c.requestMethod = "HEAD"; c.connectTimeout = 10_000; c.readTimeout = 10_000
            val code = c.responseCode
            if (code !in 200..399) error("HTTP $code")
        } finally { c.disconnect() }
    }
}
