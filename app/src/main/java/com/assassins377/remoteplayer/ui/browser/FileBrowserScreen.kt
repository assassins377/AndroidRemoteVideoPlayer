package com.assassins377.remoteplayer.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assassins377.remoteplayer.data.SmbErrorKind
import com.assassins377.remoteplayer.data.RemoteFile
import com.assassins377.remoteplayer.settings.FileSorter
import com.assassins377.remoteplayer.settings.SettingsStore
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.ui.Alignment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    serverId: String,
    onPlay: (String) -> Unit,
    onBack: () -> Unit,
    vm: FileBrowserViewModel = viewModel(),
) {
    LaunchedEffect(serverId) { vm.init(serverId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by SettingsStore.settings.collectAsStateWithLifecycle()
    val files = remember(state.files, settings.sort, settings.showHidden) {
        FileSorter.sort(state.files, settings.sort, settings.showHidden)
    }
    val onClick = { f: RemoteFile -> if (f.isDirectory) vm.open(f.path) else vm.playbackUri(f)?.let(onPlay); Unit }
    val goBack = { if (!vm.up()) onBack() }
    BackHandler(onBack = goBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.path.isBlank()) "/" else "/${state.path}") },
                navigationIcon = {
                    IconButton(onClick = goBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.padding(24.dp))
                state.error != null -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                    if (state.kind == SmbErrorKind.AUTH) Text("Измените логин/пароль в настройках сервера.")
                    Button(onClick = vm::reload) { Text("Повторить") }
                }
                settings.grid -> LazyVerticalGrid(GridCells.Adaptive(120.dp), contentPadding = PaddingValues(8.dp)) {
                    gridItems(files, key = { it.path }) { f ->
                        // Placeholder icon instead of a real video thumbnail (frame extraction over SMB is too heavy for now).
                        Card(Modifier.padding(4.dp).clickable { onClick(f) }) {
                            Column(Modifier.padding(8.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(if (f.isDirectory) Icons.Default.Folder else Icons.Default.Movie, null, Modifier.size(56.dp))
                                Text(f.name, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                else -> LazyColumn {
                    items(files, key = { it.path }) { f ->
                        ListItem(
                            leadingContent = { Icon(if (f.isDirectory) Icons.Default.Folder else Icons.Default.Movie, null) },
                            headlineContent = { Text(f.name) },
                            supportingContent = if (f.isDirectory) null else ({ Text("%.1f МБ".format(f.size / 1_048_576.0)) }),
                            modifier = Modifier.clickable { onClick(f) },
                        )
                    }
                }
            }
        }
    }
}
