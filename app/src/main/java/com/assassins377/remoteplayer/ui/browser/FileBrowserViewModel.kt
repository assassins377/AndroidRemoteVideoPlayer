package com.assassins377.remoteplayer.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assassins377.remoteplayer.data.RemoteFile
import com.assassins377.remoteplayer.data.ServerStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BrowserState(
    val path: String = "",
    val files: List<RemoteFile> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

class FileBrowserViewModel : ViewModel() {
    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state.asStateFlow()
    private var serverId: String = ""

    fun init(id: String) {
        if (serverId == id) return
        serverId = id
        open("")
    }

    fun open(path: String) {
        val server = ServerStore.get(serverId) ?: run {
            _state.value = BrowserState(error = "Сервер не найден"); return
        }
        _state.value = _state.value.copy(path = path, loading = true, error = null)
        viewModelScope.launch {
            runCatching { ServerStore.repositoryFor(server.type).list(server, path) }
                .onSuccess { _state.value = BrowserState(path = path, files = it) }
                .onFailure { _state.value = BrowserState(path = path, error = it.message ?: it.toString()) }
        }
    }

    /** Returns false when already at root. */
    fun up(): Boolean {
        val p = _state.value.path
        if (p.isBlank()) return false
        open(p.substringBeforeLast('/', ""))
        return true
    }

    fun playbackUri(file: RemoteFile): String? {
        val server = ServerStore.get(serverId) ?: return null
        return ServerStore.repositoryFor(server.type).playbackUri(server, file)
    }
}
