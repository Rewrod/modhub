package com.modhub.app.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.modhub.app.domain.ActiveDownload
import com.modhub.app.domain.describe
import kotlinx.coroutines.delay
import java.util.Locale

enum class ChipTone { OK, WARN, ERROR, NEUTRAL }

/** Small status chip: icon + label, colour by [tone]. Display-only. */
@Composable
fun StatusChip(label: String, tone: ChipTone, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (icon, container, content) = when (tone) {
        ChipTone.OK -> Triple(Icons.Filled.CheckCircle, scheme.primaryContainer, scheme.onPrimaryContainer)
        ChipTone.WARN -> Triple(Icons.Filled.Warning, scheme.tertiaryContainer, scheme.onTertiaryContainer)
        ChipTone.ERROR -> Triple(Icons.Filled.Warning, scheme.errorContainer, scheme.onErrorContainer)
        ChipTone.NEUTRAL -> Triple(Icons.Filled.Info, scheme.surfaceVariant, scheme.onSurfaceVariant)
    }
    AssistChip(
        onClick = {},
        enabled = true,
        modifier = modifier,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = container,
            labelColor = content,
            leadingIconContentColor = content,
        ),
        // Typed null: picks the BorderStroke? overload even if the old ChipBorder? one is still visible.
        border = null as BorderStroke?,
    )
}

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
    }
}

/** Centered icon + title + body + optional action, for empty/error states. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.padding(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.padding(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.padding(8.dp))
            action()
        }
    }
}

/** Tinted notice card for warnings and explanations. */
@Composable
fun NoticeCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    tone: ChipTone = ChipTone.WARN,
    actions: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        ChipTone.ERROR -> scheme.errorContainer to scheme.onErrorContainer
        ChipTone.WARN -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        ChipTone.OK -> scheme.primaryContainer to scheme.onPrimaryContainer
        ChipTone.NEUTRAL -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (actions != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { actions() }
            }
        }
    }
}

/** Bottom bar listing running/finished downloads with progress and cancel/dismiss. */
@Composable
fun DownloadsBar(
    downloads: List<ActiveDownload>,
    onCancel: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onOpenHelp: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (downloads.isEmpty()) return
    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            downloads.forEach { d -> DownloadRow(d, onCancel, onDismiss, onOpenHelp) }
        }
    }
}

@Composable
private fun DownloadRow(
    d: ActiveDownload,
    onCancel: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onOpenHelp: (String) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = when (d.state) {
                    ActiveDownload.State.PREPARING -> "Preparing..."
                    ActiveDownload.State.DOWNLOADING -> buildString {
                        append("Downloading ").append(formatBytes(d.bytesRead))
                        d.totalBytes?.let { append(" / ").append(formatBytes(it)) }
                    }
                    ActiveDownload.State.INSTALLING ->
                        if (d.installTotal > 0) "Installing\u2026 ${d.installDone}/${d.installTotal} files" else "Installing\u2026"
                    ActiveDownload.State.DONE -> "Installed"
                    ActiveDownload.State.FAILED -> "Failed: " + (d.error?.let(::describe) ?: "unknown error")
                    ActiveDownload.State.CANCELLED -> "Cancelled"
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (d.state == ActiveDownload.State.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (d.state == ActiveDownload.State.FAILED) 4 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (d.state == ActiveDownload.State.INSTALLING && d.installStartedAt != null) {
                    SlowInstallHint(d.installStartedAt)
                }
            }
            if (d.state == ActiveDownload.State.FAILED && d.helpUrl != null) {
                TextButton(onClick = { onOpenHelp(d.helpUrl) }) { Text("Open page") }
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { if (d.isFinished) onDismiss(d.id) else onCancel(d.id) }) {
                Icon(Icons.Filled.Close, contentDescription = if (d.isFinished) "Dismiss" else "Cancel download")
            }
        }
        when {
            d.isFinished -> Unit
            (d.state == ActiveDownload.State.DOWNLOADING || d.state == ActiveDownload.State.INSTALLING) &&
                d.fraction != null ->
                LinearProgressIndicator(progress = { d.fraction ?: 0f }, modifier = Modifier.fillMaxWidth())
            else -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/** After [SLOW_INSTALL_HINT_MS] in the installing phase, explains that big mods are slow. */
@Composable
private fun SlowInstallHint(startedAt: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val slow = now - startedAt >= SLOW_INSTALL_HINT_MS
    LaunchedEffect(startedAt, slow) {
        while (!slow && System.currentTimeMillis() - startedAt < SLOW_INSTALL_HINT_MS) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
        now = System.currentTimeMillis()
    }
    if (slow) {
        Text(
            "Large mods can take a few minutes on shared storage.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val SLOW_INSTALL_HINT_MS = 20_000L

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}

/** Opens [url] in a browser; returns false if nothing can handle it. */
fun openUrl(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
}

/**
 * Opens the system "All files access" page for ModHub (Android 11+). Falls back to the
 * generic list if the per-app page is missing. Returns false below Android 11.
 */
fun requestAllFilesAccess(context: Context): Boolean {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return false
    val perApp = Intent(
        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:" + context.packageName),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(perApp)
        true
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}

/** Plain-language explanation of the Android/data restriction (see docs/RESEARCH.md section 2). */
const val ANDROID_DATA_EXPLANATION =
    "This folder is inside Android/data. Since Android 11, other apps can't read or write there: " +
        "\"All files access\" explicitly excludes it, and on Android 13+ the folder picker can't select it either. " +
        "ModHub will reach it through Shizuku (a helper app started via wireless debugging, no root needed), " +
        "which isn't available yet. For now, either point SMAPI at /storage/emulated/0/StardewValley/Mods " +
        "(the SMAPI for Android default) and choose that folder, or copy mods with a PC (USB or adb)."
