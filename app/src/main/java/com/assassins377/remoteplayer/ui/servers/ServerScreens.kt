package com.assassins377.remoteplayer.ui.servers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
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
    onFavorites: () -> Unit = {},
    onLibrary: () -> Unit = {},
    onPlay: (String) -> Unit = {},
    vm: ServersViewModel = viewModel(),
) {
    val servers by vm.servers.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(com.assassins377.remoteplayer.R.string.servers)) },
                actions = {
                    IconButton(onClick = onLibrary) { Icon(Icons.Default.VideoLibrary, contentDescription = stringResource(com.assassins377.remoteplayer.R.string.library)) }
                    IconButton(onClick = onFavorites) { Icon(Icons.Default.Star, contentDescription = stringResource(com.assassins377.remoteplayer.R.string.favorites)) }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(com.assassins377.remoteplayer.R.string.settings))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = stringResource(com.assassins377.remoteplayer.R.string.add)) }
        },
    ) { padding ->
        if (servers.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(24.dp)) {
                Text(stringResource(com.assassins377.remoteplayer.R.string.no_servers))
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                item(key = "__continue") { com.assassins377.remoteplayer.ui.home.ContinueWatchingRow(onPlay) }
                items(servers, key = { it.id }) { s ->
                    ListItem(
                        headlineContent = { Text(s.name) },
                        supportingContent = { Text("${s.type} · ${s.host}${if (s.share.isNotBlank()) "/" + s.share else ""}") },
                        trailingContent = {
                            IconButton(onClick = { onEdit(s) }) { Icon(Icons.Default.Edit, contentDescription = stringResource(com.assassins377.remoteplayer.R.string.edit)) }
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
                title = { Text(if (existing == null) stringResource(com.assassins377.remoteplayer.R.string.new_server) else stringResource(com.assassins377.remoteplayer.R.string.edit_server)) },
                actions = {
                    if (existing != null) IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(com.assassins377.remoteplayer.R.string.delete))
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
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(com.assassins377.remoteplayer.R.string.name)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                host, { host = it; vm.resetTest() },
                label = { Text(if (type == ServerType.SMB) stringResource(com.assassins377.remoteplayer.R.string.host_smb) else stringResource(com.assassins377.remoteplayer.R.string.host_url)) },
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
                OutlinedTextField(user, { user = it; vm.resetTest() }, label = { Text(stringResource(com.assassins377.remoteplayer.R.string.user_hint)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    pass, { pass = it; vm.resetTest() }, label = { Text(stringResource(com.assassins377.remoteplayer.R.string.password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (type == ServerType.SMB) {
                OutlinedTextField(domain, { domain = it; vm.resetTest() }, label = { Text(stringResource(com.assassins377.remoteplayer.R.string.domain_hint)) }, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = valid && test != TestState.Running, onClick = { vm.testConnection(current()) }) {
                    Text(stringResource(com.assassins377.remoteplayer.R.string.test_connection))
                }
                Button(enabled = valid, onClick = { vm.save(current(), onDone) }) { Text(stringResource(com.assassins377.remoteplayer.R.string.save)) }
            }
            when (val t = test) {
                TestState.Idle -> {}
                TestState.Running -> LinearProgressIndicator(Modifier.fillMaxWidth())
                TestState.Ok -> Text(stringResource(com.assassins377.remoteplayer.R.string.connection_ok), color = MaterialTheme.colorScheme.primary)
                is TestState.Failed -> {
                    Text(stringResource(com.assassins377.remoteplayer.R.string.error_fmt, t.message), color = MaterialTheme.colorScheme.error)
                    t.cert?.let { c -> CertCard(c) { pin = c.sha256; vm.testConnection(current()) } }
                }
            }
            if (pin.isNotBlank() && type != ServerType.SMB) {
                Text(stringResource(com.assassins377.remoteplayer.R.string.pinned_cert, pin.uppercase().chunked(2).take(8).joinToString(":")),
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { pin = ""; vm.resetTest() }) { Text(stringResource(com.assassins377.remoteplayer.R.string.forget_cert)) }
                Text(stringResource(com.assassins377.remoteplayer.R.string.applies_after_save), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(com.assassins377.remoteplayer.R.string.delete_server_q)) },
            text = { Text(existing.name) },
            confirmButton = { TextButton(onClick = { vm.remove(existing.id); confirmDelete = false; onDone() }) { Text(stringResource(com.assassins377.remoteplayer.R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(com.assassins377.remoteplayer.R.string.cancel)) } },
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
                label = { Text(stringResource(com.assassins377.remoteplayer.R.string.share_hint)) },
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
            TextButton(enabled = enabled && state != SharesState.Loading, onClick = onLoad) { Text(stringResource(com.assassins377.remoteplayer.R.string.find_shares)) }
            when (state) {
                SharesState.Loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                is SharesState.Loaded -> if (state.names.isEmpty()) Text(stringResource(com.assassins377.remoteplayer.R.string.no_shares))
                is SharesState.Failed -> Text(stringResource(com.assassins377.remoteplayer.R.string.shares_failed, state.message),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                SharesState.Idle -> {}
            }
        }
    }
}
