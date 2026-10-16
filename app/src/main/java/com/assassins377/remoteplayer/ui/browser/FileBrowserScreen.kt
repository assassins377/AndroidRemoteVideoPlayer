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
import com.assassins377.remoteplayer.meta.ParsedTitle
import com.assassins377.remoteplayer.meta.TmdbMeta
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
    var details by remember { mutableStateOf<Triple<RemoteFile, ParsedTitle?, TmdbMeta?>?>(null) }
    val onDetails = { f: RemoteFile, p: ParsedTitle?, m: TmdbMeta? -> details = Triple(f, p, m) }
    details?.let { (f, p, m) -> DetailsSheet(serverId, f, p, m, onPlay = { details = null; onClick(f) }, onDismiss = { details = null }) }
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
                    state.cert?.let { c -> com.assassins377.remoteplayer.ui.servers.CertCard(c) { vm.trust(c) } }
                    Button(onClick = vm::reload) { Text("Повторить") }
                }
                settings.grid -> LazyVerticalGrid(GridCells.Adaptive(120.dp), contentPadding = PaddingValues(8.dp)) {
                    gridItems(files, key = { it.path }) { f -> FileCard(f, settings, serverId, { onClick(f) }, onDetails) }
                }
                else -> LazyColumn {
                    items(files, key = { it.path }) { f -> FileRow(f, settings, serverId, { onClick(f) }, onDetails) }
                }
            }
        }
    }
}
