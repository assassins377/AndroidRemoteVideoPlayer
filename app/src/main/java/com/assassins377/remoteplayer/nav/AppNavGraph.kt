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
import com.assassins377.remoteplayer.ui.servers.AddServerScreen
import com.assassins377.remoteplayer.ui.servers.ServerListScreen

object Routes {
    const val SERVERS = "servers"
    const val ADD_SERVER = "servers/add"
    const val BROWSER = "browser/{serverId}"
    const val PLAYER = "player?uri={uri}"
    fun browser(serverId: String) = "browser/$serverId"
    fun player(uri: String) = "player?uri=${Uri.encode(uri)}"
}

@Composable
fun AppNavGraph() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.SERVERS) {
        composable(Routes.SERVERS) {
            ServerListScreen(
                onAdd = { nav.navigate(Routes.ADD_SERVER) },
                onOpen = { nav.navigate(Routes.browser(it.id)) },
            )
        }
        composable(Routes.ADD_SERVER) {
            AddServerScreen(onDone = { nav.popBackStack() })
        }
        composable(Routes.BROWSER, arguments = listOf(navArgument("serverId") { type = NavType.StringType })) {
            FileBrowserScreen(
                serverId = it.arguments?.getString("serverId").orEmpty(),
                onPlay = { uri -> nav.navigate(Routes.player(uri)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.PLAYER, arguments = listOf(navArgument("uri") { type = NavType.StringType })) {
            PlayerScreen(uri = it.arguments?.getString("uri").orEmpty(), onBack = { nav.popBackStack() })
        }
    }
}
