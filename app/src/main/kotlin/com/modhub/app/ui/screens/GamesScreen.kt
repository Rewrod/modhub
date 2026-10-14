package com.modhub.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modhub.app.domain.GameStatus
import com.modhub.app.ui.components.ChipTone
import com.modhub.app.ui.components.EmptyState
import com.modhub.app.ui.components.StatusChip
import com.modhub.app.ui.screens.games.GameRow
import com.modhub.app.ui.screens.games.GamesViewModel
import com.modhub.core.model.LoaderStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesScreen(onOpenGame: (gameId: String) -> Unit, onOpenSettings: () -> Unit) {
    val viewModel: GamesViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ModHub") },
                actions = {
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Re-check") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            val anyDetected = state.games.any { it.status?.detection?.gameInstalled == true }
            when {
                state.games.isEmpty() && !state.loading -> EmptyState(
                    icon = Icons.Filled.Search,
                    title = "No games supported",
                    body = "This build has no game plugins.",
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!state.loading && !anyDetected) {
                        item {
                            Text(
                                "None of the supported games was found on this device. Install one (from Google Play " +
                                    "or the Galaxy Store), then come back; you can still open a game below to set up " +
                                    "its mods folder.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(state.games, key = { it.id }) { row -> GameCard(row, onClick = { onOpenGame(row.id) }, onPlay = { viewModel.play(row) }) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameCard(row: GameRow, onClick: () -> Unit, onPlay: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                row.launchTarget?.let { target ->
                    IconButton(onClick = onPlay) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = if (target.modded) "Play ${row.name} (modded)" else "Play ${row.name} (unmodded)",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            val status = row.status
            if (status == null) {
                Text("Checking...", style = MaterialTheme.typography.bodySmall)
            } else {
                status.detection.gameVersion?.let {
                    Text("Version $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { StatusChips(status) }
            }
        }
    }
}

/** The three detection chips, shared with the game detail header. */
@Composable
internal fun StatusChips(status: GameStatus) {
    val d = status.detection
    StatusChip(if (d.gameInstalled) "Game installed" else "Game not found", if (d.gameInstalled) ChipTone.OK else ChipTone.ERROR)
    d.loaders.forEach { loader ->
        val (label, tone) = when (loader) {
            is LoaderStatus.Installed -> "${loader.requirement.name}${loader.version?.let { " $it" } ?: ""}" to ChipTone.OK
            is LoaderStatus.Outdated -> "${loader.requirement.name} outdated" to ChipTone.WARN
            is LoaderStatus.Missing -> "${loader.requirement.name} missing" to ChipTone.ERROR
            is LoaderStatus.Unknown -> "${loader.requirement.name}: unknown" to ChipTone.WARN
        }
        StatusChip(label, tone)
    }
    val folderOk = d.modsDirectoryAccessible && status.storage.usable
    StatusChip(
        when {
            folderOk -> "Mods folder OK"
            status.restrictedPath -> "Mods folder needs Shizuku"
            !status.storage.usable -> "No storage access"
            else -> "Mods folder not found"
        },
        if (folderOk) ChipTone.OK else ChipTone.WARN,
    )
}
