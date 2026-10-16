package com.assassins377.remoteplayer.ui.servers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
    onEdit: (ServerConfig) -> Unit,
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
                            IconButton(onClick = { onEdit(s) }) { Icon(Icons.Default.Edit, contentDescription = "Изменить") }
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
fun ServerEditScreen(serverId: String?, onDone: () -> Unit, vm: ServersViewModel = viewModel()) {
    val existing = remember(serverId) { vm.get(serverId) }
    var type by remember { mutableStateOf(existing?.type ?: ServerType.SMB) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var share by remember { mutableStateOf(existing?.share ?: "") }
    var user by remember { mutableStateOf(existing?.username ?: "") }
    var pass by remember { mutableStateOf(existing?.password ?: "") }
    var domain by remember { mutableStateOf(existing?.domain ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    val test by vm.test.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { vm.resetTest() } }

    fun current() = ServerConfig(
        existing?.id ?: "", name.ifBlank { host.trim() }, type, host.trim(), share.trim(), user.trim(), pass, domain.trim(),
    )
    val valid = host.isNotBlank() && (type != ServerType.SMB || share.isNotBlank())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Новый сервер" else "Изменить сервер") },
                actions = {
                    if (existing != null) IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Удалить")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SingleChoiceSegmentedButtonRow {
                ServerType.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = type == t,
                        onClick = { type = t; vm.resetTest() },
                        shape = SegmentedButtonDefaults.itemShape(i, ServerType.entries.size),
                    ) { Text(t.name) }
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                host, { host = it; vm.resetTest() },
                label = { Text(if (type == ServerType.SMB) "Хост (192.168.1.10)" else "URL (http://...)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            if (type == ServerType.SMB) {
                OutlinedTextField(share, { share = it; vm.resetTest() }, label = { Text("Общая папка (share)") }, modifier = Modifier.fillMaxWidth())
            }
            if (type != ServerType.HTTP) {
                OutlinedTextField(user, { user = it; vm.resetTest() }, label = { Text("Пользователь (пусто = гость)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    pass, { pass = it; vm.resetTest() }, label = { Text("Пароль") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (type == ServerType.SMB) {
                OutlinedTextField(domain, { domain = it; vm.resetTest() }, label = { Text("Домен (необязательно)") }, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = valid && test != TestState.Running, onClick = { vm.testConnection(current()) }) {
                    Text("Проверить подключение")
                }
                Button(enabled = valid, onClick = { vm.save(current(), onDone) }) { Text("Сохранить") }
            }
            when (val t = test) {
                TestState.Idle -> {}
                TestState.Running -> LinearProgressIndicator(Modifier.fillMaxWidth())
                TestState.Ok -> Text("Подключение успешно", color = MaterialTheme.colorScheme.primary)
                is TestState.Failed -> Text("Ошибка: ${t.message}", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить сервер?") },
            text = { Text(existing.name) },
            confirmButton = { TextButton(onClick = { vm.remove(existing.id); confirmDelete = false; onDone() }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}
