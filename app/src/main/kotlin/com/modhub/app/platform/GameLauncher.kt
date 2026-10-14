package com.modhub.app.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.modhub.core.plugin.GamePlugin
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Something the "Play" button can start. */
data class LaunchTarget(
    val packageName: String,
    /** The app's label as the launcher shows it (falls back to the package name). */
    val label: String,
    /** True when this is a mod loader's launcher (mods load), false for the plain game. */
    val modded: Boolean,
)

/**
 * Starts a game (preferably through its mod loader). Uses
 * [GamePlugin.launchPackageNames]; on API 30+ every one of those packages must be in a
 * merged manifest `<queries>` block or `getLaunchIntentForPackage` returns null.
 */
@Singleton
class GameLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** The first launchable package of [plugin], or null if none is installed/launchable. */
    fun resolve(plugin: GamePlugin): LaunchTarget? {
        val pm = context.packageManager
        val loaderPackages = plugin.loaderRequirements.flatMapTo(HashSet()) { it.packageNames }
        for (pkg in plugin.launchPackageNames) {
            val intent = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() ?: continue
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
            // A loader launcher is "modded"; anything else (the game itself, store variants) is not.
            return LaunchTarget(packageName = intent.`package` ?: pkg, label = label, modded = pkg in loaderPackages)
        }
        return null
    }

    /** Starts [target]. Returns false if it can no longer be launched (e.g. just uninstalled). */
    fun launch(target: LaunchTarget): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(target.packageName) ?: return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
