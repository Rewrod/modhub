package com.modhub.app.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.modhub.app.ui.screens.BrowseScreen
import com.modhub.app.ui.screens.GameDetailScreen
import com.modhub.app.ui.screens.GamesScreen
import com.modhub.app.ui.screens.SettingsScreen

@Composable
fun ModHubNavGraph(
    navController: NavHostController = rememberNavController(),
    pendingNxmLink: Uri? = null,
    onNxmLinkConsumed: () -> Unit = {},
) {
    // TODO(ui/nexus agents): resolve the nxm link's domain to a GamePlugin (via sourceGameKeys)
    // and open Browse for that game with the file pre-selected. For now: just open Browse.
    LaunchedEffect(pendingNxmLink) {
        val link = pendingNxmLink ?: return@LaunchedEffect
        val domain = link.host
        if (!domain.isNullOrBlank()) navController.navigate(Routes.browse(domain))
        onNxmLinkConsumed()
    }

    NavHost(navController = navController, startDestination = Routes.GAMES) {
        composable(Routes.GAMES) {
            GamesScreen(
                onOpenGame = { gameId -> navController.navigate(Routes.gameDetail(gameId)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = Routes.GAME_DETAIL,
            arguments = listOf(navArgument(Routes.ARG_GAME_ID) { type = NavType.StringType }),
        ) { entry ->
            val gameId = entry.arguments?.getString(Routes.ARG_GAME_ID).orEmpty()
            GameDetailScreen(
                gameId = gameId,
                onBrowse = { navController.navigate(Routes.browse(gameId)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.BROWSE,
            arguments = listOf(navArgument(Routes.ARG_GAME_ID) { type = NavType.StringType }),
        ) { entry ->
            BrowseScreen(
                gameId = entry.arguments?.getString(Routes.ARG_GAME_ID).orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
