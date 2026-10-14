package com.modhub.core.model

import java.io.File

/** A mod loader or other prerequisite the game needs before mods work (e.g. SMAPI for Android). */
data class LoaderRequirement(
    val id: String,
    val name: String,
    val description: String = "",
    /** Where the user can get it. */
    val url: String? = null,
    val minimumVersion: String? = null,
    /** Android package names that indicate the loader is installed (e.g. a launcher APK). */
    val packageNames: List<String> = emptyList(),
)

/** State of a [LoaderRequirement] on this device. */
sealed interface LoaderStatus {
    val requirement: LoaderRequirement

    data class Installed(override val requirement: LoaderRequirement, val version: String? = null) : LoaderStatus
    /** Present but too old or the wrong variant; [hint] says what to do about it. */
    data class Outdated(
        override val requirement: LoaderRequirement,
        val version: String?,
        val hint: String? = null,
    ) : LoaderStatus
    data class Missing(override val requirement: LoaderRequirement, val hint: String? = null) : LoaderStatus
    /** Could not determine (e.g. the needed folder is not readable with the current storage backend). */
    data class Unknown(override val requirement: LoaderRequirement, val reason: String) : LoaderStatus
}

/** Result of [com.modhub.core.plugin.GamePlugin.detect]. */
data class GameDetection(
    val gameInstalled: Boolean,
    /** Which of the plugin's package names was found, if any. */
    val installedPackage: String? = null,
    val gameVersion: String? = null,
    val loaders: List<LoaderStatus> = emptyList(),
    /** Mods directory that will be used, and whether it exists/is reachable. */
    val modsDirectory: com.modhub.core.storage.StoragePath? = null,
    val modsDirectoryAccessible: Boolean = false,
    /** Human-readable notes to show to the user (e.g. "Needs Shizuku for Android/data"). */
    val notes: List<String> = emptyList(),
) {
    val ready: Boolean
        get() = gameInstalled && modsDirectoryAccessible && loaders.all { it is LoaderStatus.Installed }
}

/** Emitted by [com.modhub.core.source.ModSource.download]. */
sealed interface DownloadProgress {
    data object Started : DownloadProgress

    /** [totalBytes] is null when the server sends no length. */
    data class Progress(val bytesRead: Long, val totalBytes: Long?) : DownloadProgress {
        val fraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let { bytesRead.toFloat() / it }
    }

    data class Completed(val file: File) : DownloadProgress
    data class Failed(val error: Throwable) : DownloadProgress
}
