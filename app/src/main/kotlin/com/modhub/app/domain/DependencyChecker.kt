package com.modhub.app.domain

import com.modhub.core.model.GameId
import com.modhub.core.model.InstalledMod
import com.modhub.core.plugin.PluginRegistry
import com.modhub.core.storage.StoragePath
import com.modhub.stardew.StardewPlugin
import javax.inject.Inject

/** A required dependency of an installed mod that is missing, disabled or too old. Game-agnostic. */
data class DependencyIssue(
    /** Location of the mod that has the problem (unique within a game). */
    val modLocation: StoragePath,
    val modName: String,
    val dependencyId: String,
    val minimumVersion: String?,
    /** Installed version when the dependency is present but too old; null when missing. */
    val installedVersion: String?,
) {
    val message: String
        get() = when {
            installedVersion != null ->
                "Needs $dependencyId ${minimumVersion ?: ""} or later (installed: ${installedVersion.ifEmpty { "unknown" }})".trim()
            minimumVersion != null -> "Needs $dependencyId $minimumVersion or later (not installed or disabled)"
            else -> "Needs $dependencyId (not installed or disabled)"
        }
}

/**
 * Per-game dependency check, so the UI never has to know about a specific game's manifest
 * format. Contributed to a Hilt `Set<DependencyChecker>`; games without one simply show no
 * warnings.
 */
interface DependencyChecker {
    val gameId: GameId
    fun check(mods: List<InstalledMod>): List<DependencyIssue>
}

/** Adapts [StardewPlugin.dependencyReport] (SMAPI `Dependencies` / `ContentPackFor`). */
class StardewDependencyChecker @Inject constructor(
    private val registry: PluginRegistry,
) : DependencyChecker {
    override val gameId: GameId = StardewPlugin.GAME_ID

    // dependencyReport(mods) is pure, so a fresh instance is a safe fallback.
    private val plugin: StardewPlugin by lazy { registry.get(gameId) as? StardewPlugin ?: StardewPlugin() }

    override fun check(mods: List<InstalledMod>): List<DependencyIssue> =
        plugin.dependencyReport(mods).map { d ->
            DependencyIssue(
                modLocation = d.mod.location,
                modName = d.mod.name,
                dependencyId = d.missingUniqueId,
                minimumVersion = d.minimumVersion,
                installedVersion = d.installedVersion,
            )
        }
}
