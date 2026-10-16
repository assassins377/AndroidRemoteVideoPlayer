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
                state.error != null -> Text("Ошибка: ${state.error}", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                else -> LazyColumn {
                    items(state.files, key = { it.path }) { f ->
                        ListItem(
                            leadingContent = { Icon(if (f.isDirectory) Icons.Default.Folder else Icons.Default.Movie, null) },
                            headlineContent = { Text(f.name) },
                            supportingContent = if (f.isDirectory) null else ({ Text("%.1f МБ".format(f.size / 1_048_576.0)) }),
                            modifier = Modifier.clickable {
                                if (f.isDirectory) vm.open(f.path) else vm.playbackUri(f)?.let(onPlay)
                            },
                        )
                    }
                }
            }
        }
    }
}
