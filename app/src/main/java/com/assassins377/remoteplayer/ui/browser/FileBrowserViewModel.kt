package com.assassins377.remoteplayer.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assassins377.remoteplayer.data.RemoteFile
import com.assassins377.remoteplayer.data.ServerStore
import com.assassins377.remoteplayer.data.SmbError
import com.assassins377.remoteplayer.data.SmbErrorKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BrowserState(
    val path: String = "",
    /** Raw listing; UI applies sort/hidden settings via FileSorter. */
    val files: List<RemoteFile> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val kind: SmbErrorKind? = null,
    val cert: com.assassins377.remoteplayer.data.CertInfo? = null,
)

class FileBrowserViewModel : ViewModel() {
    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state.asStateFlow()
    private var serverId: String = ""

    fun init(id: String, startPath: String = "") {
        if (serverId == id) return
        serverId = id
        open(startPath)
    }

    fun open(path: String) {
        val server = ServerStore.get(serverId) ?: run {
            _state.value = BrowserState(error = com.assassins377.remoteplayer.AppStrings.get(com.assassins377.remoteplayer.R.string.err_server_missing, "Сервер не найден")); return
        }
        _state.value = _state.value.copy(path = path, loading = true, error = null)
        viewModelScope.launch {
            runCatching { ServerStore.repositoryFor(server.type).list(server, path) }
                .onSuccess { _state.value = BrowserState(path = path, files = it) }
                .onFailure { _state.value = BrowserState(path = path, error = it.message ?: it.toString(), kind = (it as? SmbError)?.kind, cert = com.assassins377.remoteplayer.data.CertInfo.from(it)) }
        }
    }

    fun reload() = open(_state.value.path)

    /** User accepted the shown certificate: pin it for this server and retry. */
    fun trust(cert: com.assassins377.remoteplayer.data.CertInfo) {
        val server = ServerStore.get(serverId) ?: return
        viewModelScope.launch { ServerStore.upsert(server.copy(pinnedCertSha256 = cert.sha256)); reload() }
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
