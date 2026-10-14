package com.modhub.app.ui.screens

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modhub.app.domain.DependencyIssue
import com.modhub.app.domain.GameSnapshot
import com.modhub.app.domain.ModRepository
import com.modhub.app.domain.describe
import com.modhub.app.ui.components.ANDROID_DATA_EXPLANATION
import com.modhub.app.ui.components.BackButton
import com.modhub.app.ui.components.ChipTone
import com.modhub.app.ui.components.DownloadsBar
import com.modhub.app.ui.components.EmptyState
import com.modhub.app.ui.components.NoticeCard
import com.modhub.app.ui.components.openUrl
import com.modhub.app.ui.components.requestAllFilesAccess
import com.modhub.app.ui.screens.detail.GameDetailViewModel
import com.modhub.core.model.InstalledMod
import com.modhub.core.model.LoaderStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailScreen(
    gameId: String,
    onBrowse: () -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val viewModel: GameDetailViewModel = hiltViewModel()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // Returning from the system settings (All files access) or after installing the game.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { msg ->
            val result = snackbar.showSnackbar(
                message = msg.text,
                actionLabel = msg.actionLabel,
                withDismissAction = msg.actionLabel == null,
                duration = if (msg.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) msg.actionUrl?.let { openUrl(context, it) }
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let(viewModel::useTreeAsModsFolder)
    }
    val pickArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(viewModel::installFromFile)
    }
    // Android 8-10: legacy runtime permission; 11+: the All files access settings page.
    val legacyStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refresh()
    }
    val fixStorageAccess = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestAllFilesAccess(context)
        } else {
            legacyStorage.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
            )
        }
        Unit
    }
    val launchFolderPicker = {
        val hint = snapshot?.status?.target?.let { ModRepository.initialTreeUri(it) }
        runCatching { pickFolder.launch(hint) }
    }

    var confirmUninstall by remember { mutableStateOf<InstalledMod?>(null) }
    var fabMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(viewModel.gameName) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { fabMenu = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add mod") },
                )
                DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Install from file") },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        onClick = {
                            fabMenu = false
                            runCatching { pickArchive.launch(arrayOf("application/zip", "*/*")) }
                        },
                    )
                    if (viewModel.hasRemoteSources) {
                        DropdownMenuItem(
                            text = { Text("Browse Nexus") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            onClick = {
                                fabMenu = false
                                onBrowse()
                            },
                        )
                    }
                }
            }
        },
        bottomBar = {
            DownloadsBar(
                downloads = downloads,
                onCancel = viewModel::cancelDownload,
                onDismiss = viewModel::dismissDownload,
                onOpenHelp = { openUrl(context, it) },
            )
        },
    ) { padding ->
        val snap = snapshot
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (snap == null) {
                if (loadError != null) {
                    EmptyState(
                        icon = Icons.Filled.Warning,
                        title = "Couldn't load ${viewModel.gameName}",
                        body = loadError.orEmpty(),
                        action = { FilledTonalButton(onClick = viewModel::refresh) { Text("Try again") } },
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                return@Column
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "header") {
                    GameHeader(
                        snap = snap,
                        onChooseFolder = { launchFolderPicker() },
                        onAllFilesAccess = fixStorageAccess,
                        onClearOverride = viewModel::clearModsFolder,
                        onOpenUrl = { openUrl(context, it) },
                    )
                }
                if (snap.status.restrictedPath) {
                    item(key = "restricted") {
                        NoticeCard(
                            title = "Android/data folder",
                            body = ANDROID_DATA_EXPLANATION,
                            tone = ChipTone.WARN,
                            actions = {
                                TextButton(onClick = { launchFolderPicker() }) { Text("Choose another folder") }
                                TextButton(onClick = onOpenSettings) { Text("Storage settings") }
                            },
                        )
                    }
                }
                loadError?.let { err ->
                    item(key = "loadError") { NoticeCard(title = "Refresh failed", body = err, tone = ChipTone.ERROR) }
                }
                snap.listError?.let { err ->
                    item(key = "listError") {
                        NoticeCard(title = "Can't read the mods folder", body = describe(err), tone = ChipTone.ERROR)
                    }
                }
                item(key = "modsTitle") {
                    Text(
                        "Installed mods (${snap.mods.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (snap.mods.isEmpty() && snap.listError == null) {
                    item(key = "empty") {
                        Text(
                            "No mods installed yet. Use \"Add mod\" to install a .zip or browse Nexus Mods.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(snap.mods, key = { it.location.value }) { mod ->
                    ModRow(
                        mod = mod,
                        issues = snap.issuesFor(mod),
                        busy = mod.location.value in busy,
                        onToggle = { viewModel.setEnabled(mod, it) },
                        onUninstall = { confirmUninstall = mod },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    confirmUninstall?.let { mod ->
        AlertDialog(
            onDismissRequest = { confirmUninstall = null },
            title = { Text("Uninstall ${mod.name}?") },
            text = { Text("This deletes ${mod.location} and everything in it, including any config the mod saved there.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUninstall = null
                    viewModel.uninstall(mod)
                }) { Text("Uninstall", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmUninstall = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameHeader(
    snap: GameSnapshot,
    onChooseFolder: () -> Unit,
    onAllFilesAccess: () -> Unit,
    onClearOverride: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val status = snap.status
    val d = status.detection
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { StatusChips(status) }
            d.gameVersion?.let { Text("Game version $it", style = MaterialTheme.typography.bodySmall) }
            Text(
                "Mods folder: ${status.target}" + if (status.modsPathOverride != null) " (custom)" else "",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Storage: ${status.storage.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = if (status.storage.usable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            d.notes.forEach { note ->
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            d.loaders.filterIsInstance<LoaderStatus.Missing>().forEach { missing ->
                missing.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                missing.requirement.url?.let { url ->
                    TextButton(onClick = { onOpenUrl(url) }) { Text("Get ${missing.requirement.name}") }
                }
            }
            val storageOk = status.storage.usable && d.modsDirectoryAccessible
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!storageOk && !status.restrictedPath && !status.hasAllFilesAccess) {
                    FilledTonalButton(onClick = onAllFilesAccess) {
                        Icon(Icons.Filled.Build, contentDescription = null)
                        Text("Fix storage access", Modifier.padding(start = 8.dp))
                    }
                }
                OutlinedButton(onClick = onChooseFolder) { Text("Choose mods folder") }
            }
            if (status.modsPathOverride != null) {
                TextButton(onClick = onClearOverride) { Text("Use the default folder again") }
            }
        }
    }
}

@Composable
private fun ModRow(
    mod: InstalledMod,
    issues: List<DependencyIssue>,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onUninstall: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(mod.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                val meta = listOfNotNull(
                    mod.version?.takeIf { it.isNotBlank() }?.let { "v$it" },
                    mod.manifest?.author?.takeIf { it.isNotBlank() }?.let { "by $it" },
                    if (mod.manifest == null) "no manifest" else null,
                    if (!mod.enabled) "disabled" else null,
                ).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                issues.forEach { issue ->
                    Text(issue.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        leadingContent = if (issues.isNotEmpty()) {
            { Icon(Icons.Filled.Warning, contentDescription = "Missing dependencies", tint = MaterialTheme.colorScheme.error) }
        } else {
            null
        },
        trailingContent = {
            Row {
                Switch(checked = mod.enabled, onCheckedChange = onToggle, enabled = !busy)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Uninstall") },
                            enabled = !busy,
                            onClick = {
                                menu = false
                                onUninstall()
                            },
                        )
                    }
                }
            }
        },
    )
}
