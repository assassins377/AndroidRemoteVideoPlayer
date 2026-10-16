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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.res.stringResource
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
    onSettings: () -> Unit = {},
    vm: ServersViewModel = viewModel(),
) {
    val servers by vm.servers.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Серверы") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(com.assassins377.remoteplayer.R.string.settings))
                    }
                },
            )
        },
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
    var pin by remember { mutableStateOf(existing?.pinnedCertSha256 ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    val test by vm.test.collectAsStateWithLifecycle()
    val shares by vm.shares.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { vm.resetTest() } }

    fun current() = ServerConfig(
        existing?.id ?: "", name.ifBlank { host.trim() }, type, host.trim(), share.trim(), user.trim(), pass, domain.trim(),
        pinnedCertSha256 = if (type == ServerType.SMB) "" else pin,
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
                SharePicker(
                    share = share,
                    onShare = { share = it },
                    state = shares,
                    enabled = host.isNotBlank(),
                    onLoad = { vm.loadShares(current().copy(id = "__shares__")) },
                )
            }
            run { // Basic/Digest for HTTP and WebDAV, NTLM for SMB
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
                is TestState.Failed -> {
                    Text("Ошибка: ${t.message}", color = MaterialTheme.colorScheme.error)
                    t.cert?.let { c -> CertCard(c) { pin = c.sha256; vm.testConnection(current()) } }
                }
            }
            if (pin.isNotBlank() && type != ServerType.SMB) {
                Text("Доверенный сертификат (SHA-256): " + pin.uppercase().chunked(2).take(8).joinToString(":") + "…",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { pin = ""; vm.resetTest() }) { Text("Забыть сертификат") }
                Text("Изменение вступит в силу после «Сохранить».", style = MaterialTheme.typography.bodySmall)
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

/** Manual share entry plus "load list" button (NetShareEnum). On failure manual entry still works. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharePicker(
    share: String,
    onShare: (String) -> Unit,
    state: SharesState,
    enabled: Boolean,
    onLoad: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(state) { if (state is SharesState.Loaded && state.names.isNotEmpty()) expanded = true }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it && state is SharesState.Loaded }) {
            OutlinedTextField(
                share, onShare,
                label = { Text("Общая папка (share)") },
                trailingIcon = { if (state is SharesState.Loaded) ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
            )
            if (state is SharesState.Loaded) {
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    state.names.forEach { n ->
                        DropdownMenuItem(text = { Text(n) }, onClick = { onShare(n); expanded = false })
                    }
                }
            }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = enabled && state != SharesState.Loading, onClick = onLoad) { Text("Найти папки на сервере") }
            when (state) {
                SharesState.Loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                is SharesState.Loaded -> if (state.names.isEmpty()) Text("Папок не найдено — введите вручную")
                is SharesState.Failed -> Text("Не удалось получить список: ${state.message}. Введите вручную.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                SharesState.Idle -> {}
            }
        }
    }
}
