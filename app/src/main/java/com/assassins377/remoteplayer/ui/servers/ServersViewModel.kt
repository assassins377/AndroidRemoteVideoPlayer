package com.assassins377.remoteplayer.ui.servers

import androidx.lifecycle.ViewModel
import com.assassins377.remoteplayer.data.ServerConfig
import com.assassins377.remoteplayer.data.ServerStore
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

class ServersViewModel : ViewModel() {
    val servers: StateFlow<List<ServerConfig>> = ServerStore.servers

    fun add(server: ServerConfig) = ServerStore.add(server.copy(id = UUID.randomUUID().toString()))
    fun remove(id: String) = ServerStore.remove(id)
}
