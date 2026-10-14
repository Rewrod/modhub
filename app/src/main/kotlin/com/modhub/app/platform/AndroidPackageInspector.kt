package com.modhub.app.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.modhub.core.plugin.PackageInspector
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PackageManager-backed [PackageInspector]. On API 30+ a package is only visible if it is
 * declared in a merged manifest `<queries>` block (each game plugin declares its own).
 */
@Singleton
class AndroidPackageInspector @Inject constructor(
    @ApplicationContext private val context: Context,
) : PackageInspector {

    override fun isInstalled(packageName: String): Boolean = versionNameOrNull(packageName, requireFound = true) != null

    override fun versionName(packageName: String): String? = versionNameOrNull(packageName, requireFound = false)

    private fun versionNameOrNull(packageName: String, requireFound: Boolean): String? = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        }
        info.versionName ?: if (requireFound) "" else null
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
