package com.modhub.app.ui.navigation

import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    // Activity-scoped (called outside NavHost, so the owner is the activity).
    val appViewModel: AppViewModel = hiltViewModel()

    // nxm:// link -> AppViewModel starts the download job -> we open that game's detail
    // screen, whose downloads bar shows progress.
    LaunchedEffect(pendingNxmLink) {
        val link = pendingNxmLink ?: return@LaunchedEffect
        appViewModel.onNxmLink(link.toString())
        onNxmLinkConsumed()
    }
    LaunchedEffect(Unit) {
        appViewModel.openGame.collect { gameId ->
            navController.navigate(Routes.gameDetail(gameId)) {
                popUpTo(Routes.GAMES)
                launchSingleTop = true
            }
        }
    }
    val nxmError by appViewModel.error.collectAsStateWithLifecycle()
    nxmError?.let { message ->
        AlertDialog(
            onDismissRequest = appViewModel::dismissError,
            title = { Text("Can't use this download link") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = appViewModel::dismissError) { Text("OK") } },
        )
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
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = Routes.BROWSE,
            arguments = listOf(navArgument(Routes.ARG_GAME_ID) { type = NavType.StringType }),
        ) { entry ->
            BrowseScreen(
                gameId = entry.arguments?.getString(Routes.ARG_GAME_ID).orEmpty(),
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
