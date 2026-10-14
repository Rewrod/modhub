package com.modhub.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.modhub.app.ui.theme.ModHubTheme

/*
 * Placeholder screens. The UI agent replaces each with a real screen + @HiltViewModel,
 * keeping the public signatures (the nav graph depends on them).
 */

@Composable
fun GamesScreen(onOpenGame: (gameId: String) -> Unit, onOpenSettings: () -> Unit) {
    StubScreen(title = "Games") {
        // TODO(ui): list PluginRegistry.all() with GamePlugin.detect() status.
        Button(onClick = { onOpenGame("stardew") }) { Text("Stardew Valley") }
        TextButton(onClick = onOpenSettings) { Text("Settings") }
    }
}

@Composable
fun GameDetailScreen(gameId: String, onBrowse: () -> Unit, onBack: () -> Unit) {
    StubScreen(title = "Mods: $gameId", onBack = onBack) {
        // TODO(ui): installed mods via GamePlugin.listInstalled(); toggle, uninstall, load order.
        Button(onClick = onBrowse) { Text("Browse mods") }
    }
}

@Composable
fun BrowseScreen(gameId: String, onBack: () -> Unit) {
    StubScreen(title = "Browse: $gameId", onBack = onBack) {
        // TODO(ui): SourceRegistry.sourcesFor(plugin) -> listLatest/search -> getFiles -> download -> install.
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    StubScreen(title = "Settings", onBack = onBack) {
        // TODO(ui): SettingsRepository: Nexus API key, storage mode, per-game mods path, SAF tree picker.
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StubScreen(
    title: String,
    onBack: (() -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

@Preview
@Composable
private fun GamesScreenPreview() {
    ModHubTheme { GamesScreen(onOpenGame = {}, onOpenSettings = {}) }
}
