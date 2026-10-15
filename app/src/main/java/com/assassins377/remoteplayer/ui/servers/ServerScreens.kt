package com.assassins377.remoteplayer.ui.servers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assassins377.remoteplayer.data.ServerConfig
import com.assassins377.remoteplayer.data.ServerType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    onAdd: () -> Unit,
    onOpen: (ServerConfig) -> Unit,
    vm: ServersViewModel = viewModel(),
) {
    val servers by vm.servers.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Серверы") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "Добавить") }
        },
    ) { padding ->
        if (servers.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(24.dp)) {
                Text("Нет серверов. Нажмите +, чтобы добавить SMB, HTTP или WebDAV.")
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(servers, key = { it.id }) { s ->
                    ListItem(
                        headlineContent = { Text(s.name) },
                        supportingContent = { Text("${s.type} · ${s.host}${if (s.share.isNotBlank()) "/" + s.share else ""}") },
                        trailingContent = {
                            IconButton(onClick = { vm.remove(s.id) }) { Icon(Icons.Default.Delete, contentDescription = "Удалить") }
                        },
                        modifier = Modifier.clickable { onOpen(s) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerScreen(onDone: () -> Unit, vm: ServersViewModel = viewModel()) {
    var type by remember { mutableStateOf(ServerType.SMB) }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    Scaffold(topBar = { TopAppBar(title = { Text("Новый сервер") }) }) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SingleChoiceSegmentedButtonRow {
                ServerType.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = type == t,
                        onClick = { type = t },
                        shape = SegmentedButtonDefaults.itemShape(i, ServerType.entries.size),
                    ) { Text(t.name) }
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                host, { host = it },
                label = { Text(if (type == ServerType.SMB) "Хост (192.168.1.10)" else "URL (http://...)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            if (type == ServerType.SMB) {
                OutlinedTextField(share, { share = it }, label = { Text("Общая папка (share)") }, modifier = Modifier.fillMaxWidth())
            }
            if (type != ServerType.HTTP) {
                OutlinedTextField(user, { user = it }, label = { Text("Пользователь (пусто = гость)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("Пароль") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                enabled = host.isNotBlank() && (type != ServerType.SMB || share.isNotBlank()),
                onClick = {
                    vm.add(ServerConfig("", name.ifBlank { host }, type, host.trim(), share.trim(), user, pass))
                    onDone()
                },
            ) { Text("Сохранить") }
        }
    }
}
