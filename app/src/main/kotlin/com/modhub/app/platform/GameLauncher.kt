package com.modhub.app.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.modhub.app.storage.BridgeClient
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
    /**
     * The loader launcher speaks ModHub's bridge, so "Play" can ask it to start the game at once
     * (`modhub.autoStart`) instead of showing its own UI.
     */
    val autoStart: Boolean = false,
    /**
     * The bridge says the loader itself (SMAPI) isn't set up inside the launcher yet: "Play"
     * becomes "Set up SMAPI in launcher" and just opens the launcher.
     */
    val needsLoaderSetup: Boolean = false,
)

/**
 * Starts a game (preferably through its mod loader). Uses
 * [GamePlugin.launchPackageNames]; on API 30+ every one of those packages must be in a
 * merged manifest `<queries>` block or `getLaunchIntentForPackage` returns null.
 */
@Singleton
class GameLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bridge: BridgeClient,
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
            val modded = pkg in loaderPackages
            // Bridge status only matters for the loader's launcher (the bridge lives in that package).
            val bridgeInfo = plugin.loaderBridgeAuthority
                ?.takeIf { modded && bridge.isAvailable(it) }
                ?.let { bridge.info(it) }
                ?.takeIf { it.supported }
            return LaunchTarget(
                packageName = intent.`package` ?: pkg,
                label = label,
                modded = modded,
                autoStart = bridgeInfo?.smapiInstalled == true,
                needsLoaderSetup = bridgeInfo != null && !bridgeInfo.smapiInstalled,
            )
        }
        return null
    }

    /**
     * Starts [target]. A bridge-capable loader launcher gets [EXTRA_AUTO_START] so it starts the
     * game straight away (docs/BRIDGE.md); while SMAPI still needs setting up it opens normally.
     * Returns false if it can no longer be launched (e.g. just uninstalled).
     */
    fun launch(target: LaunchTarget): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(target.packageName) ?: return false
        if (target.modded && target.autoStart && !target.needsLoaderSetup) intent.putExtra(EXTRA_AUTO_START, true)
        // The user may set up SMAPI in the launcher now; re-read bridge status when they come back.
        if (target.modded) bridge.invalidate()
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    companion object {
        /** ModHub Launcher's "start the game now" extra (docs/BRIDGE.md, "Auto-start"). */
        const val EXTRA_AUTO_START = "modhub.autoStart"
    }
}
