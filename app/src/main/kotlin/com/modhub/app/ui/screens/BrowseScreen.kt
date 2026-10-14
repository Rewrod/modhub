package com.modhub.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.modhub.app.ui.components.BackButton
import com.modhub.app.ui.components.DownloadsBar
import com.modhub.app.ui.components.EmptyState
import com.modhub.app.ui.components.formatBytes
import com.modhub.app.ui.components.openUrl
import com.modhub.app.ui.screens.browse.BrowseViewModel
import com.modhub.app.ui.screens.browse.FilesSheet
import com.modhub.core.model.ModFile
import com.modhub.core.model.ModFileCategory
import com.modhub.core.model.ModInfo
import com.modhub.core.source.ModListing
import com.modhub.nexus.RateLimitInfo

private val LISTING_TABS = listOf(
    ModListing.LATEST_UPDATED to "Latest updated",
    ModListing.LATEST_ADDED to "Latest added",
    ModListing.TRENDING to "Trending",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(gameId: String, onBack: () -> Unit, onOpenSettings: () -> Unit = {}) {
    val viewModel: BrowseViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rateLimit by viewModel.rateLimit.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.checkConfiguredAndLoad() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Browse ${viewModel.sourceName}")
                        Text(viewModel.gameName, style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (state.configured == true) {
                        IconButton(onClick = viewModel::reload) { Icon(Icons.Filled.Refresh, contentDescription = "Reload") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                DownloadsBar(
                    downloads = downloads,
                    onCancel = viewModel::cancelDownload,
                    onDismiss = viewModel::dismissDownload,
                    onOpenHelp = { openUrl(context, it) },
                )
                RateLimitFooter(rateLimit)
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (state.configured) {
                null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                false -> EmptyState(
                    icon = Icons.Filled.Lock,
                    title = "Nexus Mods API key needed",
                    body = "ModHub talks to Nexus Mods with your personal API key. Get it from nexusmods.com " +
                        "(Site preferences, API Keys), then paste it in Settings.",
                    action = { Button(onClick = onOpenSettings) { Text("Open Settings") } },
                )
                true -> BrowseContent(
                    state = state,
                    searchNote = viewModel.searchNote,
                    onListing = viewModel::selectListing,
                    onQuery = viewModel::setQuery,
                    onRetry = viewModel::reload,
                    onOpenMod = viewModel::openFiles,
                )
            }
        }
    }

    state.sheet?.let { sheet ->
        FilesBottomSheet(
            sheet = sheet,
            onDismiss = viewModel::closeFiles,
            onInstall = { file -> viewModel.downloadAndInstall(sheet.mod, file) },
            onOpenPage = { url -> openUrl(context, url) },
        )
    }
}

@Composable
private fun BrowseContent(
    state: com.modhub.app.ui.screens.browse.BrowseUiState,
    searchNote: String,
    onListing: (ModListing) -> Unit,
    onQuery: (String) -> Unit,
    onRetry: () -> Unit,
    onOpenMod: (ModInfo) -> Unit,
) {
    val selected = LISTING_TABS.indexOfFirst { it.first == state.listing }.coerceAtLeast(0)
    TabRow(selectedTabIndex = selected) {
        LISTING_TABS.forEachIndexed { i, (listing, label) ->
            Tab(selected = i == selected, onClick = { onListing(listing) }, text = { Text(label, maxLines = 1) })
        }
    }
    OutlinedTextField(
        value = state.query,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (state.query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear") }
            }
        },
        placeholder = { Text("Filter these mods") },
        supportingText = { Text(searchNote, style = MaterialTheme.typography.bodySmall) },
    )
    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    val mods = state.visibleMods
    when {
        state.error != null && state.mods.isEmpty() -> EmptyState(
            icon = Icons.Filled.Warning,
            title = "Couldn't load mods",
            body = state.error,
            action = { FilledTonalButton(onClick = onRetry) { Text("Try again") } },
        )
        !state.loading && mods.isEmpty() -> EmptyState(
            icon = Icons.Filled.Search,
            title = if (state.query.isBlank()) "Nothing here" else "No matches",
            body = if (state.query.isBlank()) "This list is empty right now." else "No mod in this list matches \"${state.query}\".",
        )
        else -> LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(mods, key = { it.id.value }) { mod -> ModCard(mod, onClick = { onOpenMod(mod) }) }
        }
    }
}

@Composable
private fun ModCard(mod: ModInfo, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (mod.pictureUrl != null) {
                AsyncImage(
                    model = mod.pictureUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(96.dp).aspectRatio(1f),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(mod.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val byline = listOfNotNull(
                    mod.author.takeIf { it.isNotBlank() }?.let { "by $it" },
                    mod.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                ).joinToString(" · ")
                if (byline.isNotEmpty()) {
                    Text(byline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (mod.summary.isNotBlank()) {
                    Text(mod.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilesBottomSheet(
    sheet: FilesSheet,
    onDismiss: () -> Unit,
    onInstall: (ModFile) -> Unit,
    onOpenPage: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(sheet.mod.name, style = MaterialTheme.typography.titleLarge)
            if (sheet.mod.author.isNotBlank()) {
                Text("by ${sheet.mod.author}", style = MaterialTheme.typography.bodySmall)
            }
            sheet.mod.pageUrl?.let { url ->
                OutlinedButton(onClick = { onOpenPage(url) }, modifier = Modifier.padding(vertical = 8.dp)) {
                    Text("Open on Nexus")
                }
            }
            Text(
                "Non-Premium accounts: open the mod on Nexus and tap \"Mod Manager Download\"; ModHub picks up the link.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(4.dp))
            HorizontalDivider()
        }
        when {
            sheet.loading -> Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            sheet.error != null -> Text(
                sheet.error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
            sheet.files.isEmpty() -> Text("No downloadable files.", modifier = Modifier.padding(16.dp))
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(sheet.files, key = { it.id }) { file ->
                    ListItem(
                        headlineContent = { Text(file.name.ifBlank { file.fileName }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    categoryLabel(file.category),
                                    file.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                                    file.sizeBytes?.let(::formatBytes),
                                ).joinToString(" · "),
                            )
                        },
                        trailingContent = {
                            FilledTonalButton(onClick = { onInstall(file) }) { Text("Install") }
                        },
                    )
                }
            }
        }
    }
}

private fun categoryLabel(c: ModFileCategory): String = when (c) {
    ModFileCategory.MAIN -> "Main"
    ModFileCategory.UPDATE -> "Update"
    ModFileCategory.OPTIONAL -> "Optional"
    ModFileCategory.OLD_VERSION -> "Old version"
    ModFileCategory.MISCELLANEOUS -> "Misc"
    ModFileCategory.ARCHIVED -> "Archived"
    ModFileCategory.UNKNOWN -> "File"
}

@Composable
private fun RateLimitFooter(info: RateLimitInfo?) {
    if (info == null) return
    val parts = listOfNotNull(
        info.hourlyRemaining?.let { r -> "Hourly: $r" + (info.hourlyLimit?.let { "/$it" } ?: "") },
        info.dailyRemaining?.let { r -> "Daily: $r" + (info.dailyLimit?.let { "/$it" } ?: "") },
    )
    if (parts.isEmpty()) return
    Text(
        "Nexus API requests left: " + parts.joinToString("  "),
        style = MaterialTheme.typography.labelSmall,
        color = if (info.exhausted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}
