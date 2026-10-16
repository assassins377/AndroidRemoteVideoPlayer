package com.assassins377.remoteplayer.nav

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.assassins377.remoteplayer.ui.browser.FileBrowserScreen
import com.assassins377.remoteplayer.ui.player.PlayerScreen
import com.assassins377.remoteplayer.ui.servers.ServerEditScreen
import com.assassins377.remoteplayer.ui.servers.ServerListScreen
import com.assassins377.remoteplayer.ui.settings.SettingsScreen

object Routes {
    const val SERVERS = "servers"
    const val SETTINGS = "settings"
    const val EDIT_SERVER = "servers/edit?id={id}"
    fun editServer(id: String? = null) = if (id == null) "servers/edit" else "servers/edit?id=${Uri.encode(id)}"
    const val BROWSER = "browser/{serverId}?path={path}"
    const val FAVORITES = "favorites"
    const val LIBRARY = "library"
    const val PLAYER = "player?uri={uri}"
    fun browser(serverId: String, path: String = "") = "browser/${Uri.encode(serverId)}?path=${Uri.encode(path)}"
    fun player(uri: String) = "player?uri=${Uri.encode(uri)}"
}

@Composable
fun AppNavGraph() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.SERVERS) {
        composable(Routes.SERVERS) {
            ServerListScreen(
                onAdd = { nav.navigate(Routes.editServer()) },
                onEdit = { nav.navigate(Routes.editServer(it.id)) },
                onOpen = { nav.navigate(Routes.browser(it.id)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onFavorites = { nav.navigate(Routes.FAVORITES) },
                onLibrary = { nav.navigate(Routes.LIBRARY) },
                onPlay = { uri -> nav.navigate(Routes.player(uri)) },
            )
        }
        composable(Routes.FAVORITES) {
            com.assassins377.remoteplayer.ui.home.FavoritesScreen(
                onOpenFolder = { f -> nav.navigate(Routes.browser(f.serverId, f.path)) },
                onPlay = { uri -> nav.navigate(Routes.player(uri)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.LIBRARY) {
            com.assassins377.remoteplayer.ui.library.LibraryScreen(
                onPlay = { uri -> nav.navigate(Routes.player(uri)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
        composable(
            Routes.EDIT_SERVER,
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) {
            ServerEditScreen(serverId = it.arguments?.getString("id"), onDone = { nav.popBackStack() })
        }
        composable(Routes.BROWSER, arguments = listOf(navArgument("serverId") { type = NavType.StringType }, navArgument("path") { type = NavType.StringType; defaultValue = "" })) {
            FileBrowserScreen(
                serverId = it.arguments?.getString("serverId").orEmpty(),
                startPath = it.arguments?.getString("path").orEmpty(),
                onPlay = { uri -> nav.navigate(Routes.player(uri)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.PLAYER, arguments = listOf(navArgument("uri") { type = NavType.StringType })) {
            PlayerScreen(uri = it.arguments?.getString("uri").orEmpty(), onBack = { nav.popBackStack() })
        }
    }
}
