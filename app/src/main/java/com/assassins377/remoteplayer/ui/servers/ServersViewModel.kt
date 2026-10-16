package com.assassins377.remoteplayer.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assassins377.remoteplayer.data.ServerConfig
import com.assassins377.remoteplayer.data.ServerStore
import com.assassins377.remoteplayer.data.ServerType
import com.assassins377.remoteplayer.data.SmbRepository
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
    data class Failed(val message: String) : TestState
}

class ServersViewModel : ViewModel() {
    val servers: StateFlow<List<ServerConfig>> = ServerStore.servers

    private val _test = MutableStateFlow<TestState>(TestState.Idle)
    val test: StateFlow<TestState> = _test.asStateFlow()
    private var testJob: Job? = null

    fun get(id: String?): ServerConfig? = id?.let(ServerStore::get)

    fun save(server: ServerConfig, onSaved: () -> Unit) = viewModelScope.launch {
        ServerStore.upsert(if (server.id.isBlank()) server.copy(id = UUID.randomUUID().toString()) else server)
        onSaved()
    }

    fun remove(id: String) = viewModelScope.launch { ServerStore.remove(id) }

    fun resetTest() { testJob?.cancel(); _test.value = TestState.Idle }

    fun testConnection(server: ServerConfig) {
        testJob?.cancel()
        _test.value = TestState.Running
        testJob = viewModelScope.launch {
            _test.value = runCatching {
                withTimeout(15_000) {
                    when (server.type) {
                        ServerType.SMB -> SmbRepository().testConnection(server)
                        ServerType.HTTP -> httpHead(server.host)
                        ServerType.WEBDAV -> error("WebDAV пока не поддерживается")
                    }
                }
            }.fold({ TestState.Ok }, { TestState.Failed(it.message ?: it.toString()) })
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
