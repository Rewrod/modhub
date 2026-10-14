package com.modhub.app.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modhub.app.BuildConfig
import com.modhub.app.data.settings.StorageMode
import com.modhub.app.ui.components.BackButton
import com.modhub.app.ui.components.openUrl
import com.modhub.app.ui.components.requestAllFilesAccess
import com.modhub.app.devtools.GameAssemblyExporter
import com.modhub.app.ui.screens.settings.AssemblyExport
import com.modhub.app.ui.screens.settings.KeyCheck
import com.modhub.app.ui.screens.settings.SettingsViewModel

private const val NEXUS_API_KEYS_URL = "https://www.nexusmods.com/settings/api-keys"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val keyDraft by viewModel.keyDraft.collectAsStateWithLifecycle()
    val keyCheck by viewModel.keyCheck.collectAsStateWithLifecycle()
    val savedKey by viewModel.savedKey.collectAsStateWithLifecycle()
    val mode by viewModel.storageMode.collectAsStateWithLifecycle()
    val folders by viewModel.gameFolders.collectAsStateWithLifecycle()
    val allFiles by viewModel.allFilesAccess.collectAsStateWithLifecycle()
    val gamePackages by viewModel.gamePackages.collectAsStateWithLifecycle()
    val assemblyExport by viewModel.assemblyExport.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            // ------------------------------------------------------------ Nexus
            item { SectionTitle("Nexus Mods") }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    var visible by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = viewModel::setKeyDraft,
                        label = { Text("Personal API key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        trailingIcon = {
                            IconButton(onClick = { visible = !visible }) {
                                Icon(Icons.Filled.Lock, contentDescription = if (visible) "Hide key" else "Show key")
                            }
                        },
                        supportingText = {
                            Text(
                                if (savedKey == null) "Not set. Stored on this device only."
                                else "Saved. Stored on this device only.",
                            )
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = viewModel::saveAndValidateKey, enabled = keyCheck != KeyCheck.Checking) {
                            Text("Save & validate")
                        }
                        if (savedKey != null) TextButton(onClick = viewModel::clearKey) { Text("Remove") }
                        TextButton(onClick = { openUrl(context, NEXUS_API_KEYS_URL) }) { Text("Get a key") }
                    }
                    KeyCheckLine(keyCheck)
                }
            }
            item { HorizontalDivider(Modifier.padding(top = 16.dp)) }

            // ------------------------------------------------------------ Storage
            item { SectionTitle("Storage") }
            val modes = listOf(
                Triple(StorageMode.AUTO, "Automatic", "Direct file access when allowed, else the folder you picked."),
                Triple(StorageMode.FILE, "Direct file", "Needs All files access on Android 11+. Can't reach Android/data."),
                Triple(StorageMode.SAF, "Picked folder (SAF)", "Uses the folder chosen with \"Choose mods folder\" on the game screen."),
                Triple(StorageMode.SHIZUKU, "Shizuku", if (viewModel.shizukuAvailable) "Privileged access, works for Android/data." else "Coming soon. Needed for Android/data folders."),
            )
            modes.forEach { (m, title, desc) ->
                val enabled = m != StorageMode.SHIZUKU || viewModel.shizukuAvailable
                item(key = "mode-${m.name}") {
                    ListItem(
                        modifier = Modifier.selectable(
                            selected = mode == m,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { viewModel.setStorageMode(m) },
                        ),
                        headlineContent = {
                            Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                        },
                        supportingContent = { Text(desc) },
                        leadingContent = { RadioButton(selected = mode == m, onClick = null, enabled = enabled) },
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (allFiles) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Text("All files access granted", style = MaterialTheme.typography.bodyMedium)
                            }
                        } else {
                            FilledTonalButton(onClick = { requestAllFilesAccess(context) }) { Text("Request All files access") }
                        }
                        Text(
                            "All files access lets ModHub write shared folders such as /storage/emulated/0/StardewValley/Mods. " +
                                "It does not cover Android/data or Android/obb; those need Shizuku.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { SectionTitle("Mods folders", small = true) }
            if (folders.isEmpty()) {
                item { Text("No games.", Modifier.padding(horizontal = 16.dp)) }
            }
            folders.forEach { row ->
                item(key = "folder-${row.gameId.value}") {
                    ListItem(
                        headlineContent = { Text(row.name) },
                        supportingContent = {
                            Text(
                                row.settings.modsPathOverride?.value?.let { p ->
                                    p + if (row.settings.safTreeUri != null) " (picked folder)" else ""
                                } ?: "Default (detected automatically)",
                            )
                        },
                        trailingContent = {
                            if (row.settings.modsPathOverride != null || row.settings.safTreeUri != null) {
                                TextButton(onClick = { viewModel.clearGameFolder(row.gameId) }) { Text("Clear") }
                            }
                        },
                    )
                }
            }
            item { HorizontalDivider(Modifier.padding(top = 8.dp)) }

            // ------------------------------------------------------------ Developer
            item { SectionTitle("Developer") }
            if (gamePackages.isEmpty()) {
                item {
                    ListItem(
                        headlineContent = { Text(EXPORT_ASSEMBLIES_TITLE, color = MaterialTheme.colorScheme.outline) },
                        supportingContent = { Text("Stardew Valley is not installed.") },
                    )
                }
            }
            gamePackages.forEach { game ->
                item(key = "export-${game.packageName}") {
                    val running = assemblyExport is AssemblyExport.Running
                    val mine = when (val e = assemblyExport) {
                        is AssemblyExport.Running -> e.packageName == game.packageName
                        is AssemblyExport.Done -> e.packageName == game.packageName
                        is AssemblyExport.Failed -> e.packageName == game.packageName
                        AssemblyExport.Idle -> false
                    }
                    ListItem(
                        modifier = Modifier.clickable(enabled = !running) { viewModel.exportGameAssemblies(game.packageName) },
                        headlineContent = { Text(EXPORT_ASSEMBLIES_TITLE) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "${game.label} ${game.versionName}: zips " +
                                        GameAssemblyExporter.REQUIRED_DLLS.joinToString() +
                                        " from your installed game and opens the share sheet. Needed as compile " +
                                        "references to build the ModHub Launcher; don't redistribute them.",
                                )
                                if (mine) AssemblyExportLine(assemblyExport)
                            }
                        },
                        trailingContent = {
                            if (running && mine) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        },
                    )
                }
            }
            item { HorizontalDivider(Modifier.padding(top = 8.dp)) }

            // ------------------------------------------------------------ About
            item { SectionTitle("About") }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ModHub ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Stardew Valley mods run on SMAPI by Pathoschild and contributors, through the unofficial " +
                            "community SMAPI for Android port. ModHub only manages the mods folder; install SMAPI " +
                            "for Android separately.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "ModHub is not affiliated with or endorsed by ConcernedApe, Chucklefish or Nexus Mods. " +
                            "Mod data and downloads come from the Nexus Mods API using your own API key.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { openUrl(context, "https://smapi.io") }) { Text("smapi.io") }
                }
            }
        }
    }
}

private const val EXPORT_ASSEMBLIES_TITLE = "Export game assemblies (for launcher build)"

@Composable
private fun AssemblyExportLine(state: AssemblyExport) {
    when (state) {
        AssemblyExport.Idle -> Unit
        is AssemblyExport.Running -> Text("${state.step}...", style = MaterialTheme.typography.bodySmall)
        is AssemblyExport.Done -> Text(
            "Exported ${state.fileName}. Tap to export and share again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        is AssemblyExport.Failed -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SectionTitle(text: String, small: Boolean = false) {
    Text(
        text,
        style = if (small) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun KeyCheckLine(check: KeyCheck) {
    when (check) {
        KeyCheck.Idle -> Unit
        KeyCheck.Checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Checking key...", style = MaterialTheme.typography.bodySmall)
        }
        is KeyCheck.Valid -> Column {
            Text(
                "Signed in as ${check.userName}" + when {
                    check.premium -> " (Premium)"
                    check.supporter -> " (Supporter)"
                    else -> ""
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (!check.premium) {
                Text(
                    "Without Premium, start downloads from the website with \"Mod Manager Download\"; ModHub " +
                        "handles the nxm:// link it opens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is KeyCheck.Invalid -> Text(check.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
