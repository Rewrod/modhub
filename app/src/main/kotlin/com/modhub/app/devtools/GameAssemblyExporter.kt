package com.modhub.app.devtools

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.modhub.app.devtools.assemblystore.ApkAssemblyScanner
import com.modhub.app.devtools.assemblystore.ApkScanResult
import com.modhub.app.devtools.assemblystore.ExtractedAssembly
import com.modhub.app.domain.UserFacingException
import com.modhub.app.platform.LauncherInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** An installed copy of Stardew Valley that assemblies can be exported from. */
data class InstalledGamePackage(val packageName: String, val label: String, val versionName: String)

/**
 * Developer tool: exports `StardewValley.dll`, `StardewValley.GameData.dll` and
 * `MonoGame.Framework.dll` from the user's installed Stardew Valley so the ModHub Launcher
 * (`launcher/`, C#) can be built against them. They must come from the user's own copy of the game.
 *
 * Reads the base APK and every split (`ApplicationInfo.sourceDir` + `splitSourceDirs`), like
 * the launcher's `GameAssemblyManager`/`StardewApkTool` (Play Store: assemblies live in the
 * `split_config.arm64_v8a` APK; Galaxy Store: in the base APK). Parsing is in the pure-JVM
 * [com.modhub.app.devtools.assemblystore] package.
 */
@Singleton
class GameAssemblyExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Installed game packages, Play Store first. */
    fun installedPackages(): List<InstalledGamePackage> = GAME_PACKAGES.mapNotNull { (pkg, store) ->
        val info = packageInfo(pkg) ?: return@mapNotNull null
        InstalledGamePackage(pkg, "Stardew Valley ($store)", info.versionName ?: "unknown")
    }

    /**
     * Writes `cacheDir/devtools/game-assemblies-<versionName>.zip` with the three DLLs, a
     * `README.txt` and a `manifest.json` (sha256 per DLL). [onProgress] gets short status lines.
     * Throws [UserFacingException] when the game is missing or a DLL can't be found.
     */
    suspend fun export(packageName: String, onProgress: (String) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val info = packageInfo(packageName)
            ?: throw UserFacingException("$packageName is not installed (or not visible to ModHub).")
        val app = info.applicationInfo ?: throw UserFacingException("$packageName has no application info.")
        val versionName = info.versionName ?: "unknown"
        val apks = buildList {
            app.sourceDir?.let { add(it) }
            // Arch split (split_config.arm64_v8a.apk) first after the base: that's where .NET 9 builds keep the stores.
            app.splitSourceDirs.orEmpty().sortedBy { if (it.contains("arm64")) 0 else 1 }.forEach { add(it) }
        }.distinct()
        if (apks.isEmpty()) throw UserFacingException("Android reported no APK files for $packageName.")

        val found = LinkedHashMap<String, Pair<String, ExtractedAssembly>>() // key -> (apk path, assembly)
        val scanned = ArrayList<Pair<String, String>>() // apk path -> description
        val scanner = ApkAssemblyScanner { Log.w(TAG, it) }
        for (apk in apks) {
            if (found.size == REQUIRED_DLLS.size) break
            ensureActive()
            val apkName = File(apk).name
            onProgress("Reading $apkName")
            val remaining = REQUIRED_DLLS.filter { key(it) !in found }
            val result: ApkScanResult = try {
                scanner.scan(File(apk), remaining)
            } catch (e: IOException) {
                scanned += apk to "unreadable (${e.message})"
                continue
            } catch (e: RuntimeException) {
                Log.w(TAG, "Scanning $apk failed", e)
                scanned += apk to "failed (${e.message})"
                continue
            }
            scanned += apk to result.describe() + result.extracted.values.joinToString(prefix = "; found: ") { it.fileName }
                .takeIf { result.extracted.isNotEmpty() }.orEmpty()
            result.extracted.forEach { (k, a) -> found.putIfAbsent(k, apk to a) }
        }

        val missing = REQUIRED_DLLS.filter { key(it) !in found }
        if (missing.isNotEmpty()) {
            throw UserFacingException(
                buildString {
                    append("Couldn't find ${missing.joinToString()} in $packageName $versionName.")
                    if (found.isNotEmpty()) append(" Found: ${found.values.joinToString { it.second.fileName }}.")
                    append(" Scanned: ")
                    append(scanned.joinToString("; ") { (path, desc) -> "${File(path).name} ($desc)" })
                    append('.')
                },
            )
        }

        onProgress("Writing zip")
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { if (it.name.startsWith(ZIP_PREFIX)) it.delete() } // keep one export around
        val zip = File(dir, "$ZIP_PREFIX${safe(versionName)}.zip")
        val part = File(dir, zip.name + ".part")
        val exportedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val files = REQUIRED_DLLS.map { found.getValue(key(it)) }
        val hashes = files.map { (_, a) -> sha256(a.bytes) }
        try {
            ZipOutputStream(part.outputStream().buffered()).use { out ->
                files.forEach { (_, a) -> out.entry(a.fileName, a.bytes) }
                out.entry("README.txt", readme(info, apks, files, hashes, scanned, exportedAt).toByteArray())
                out.entry("manifest.json", manifestJson(info, files, hashes, exportedAt).toByteArray())
            }
            if (zip.exists()) zip.delete()
            if (!part.renameTo(zip)) throw IOException("Couldn't save ${zip.name}")
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        zip
    }

    /** Opens the share sheet for an exported zip (FileProvider content URI). */
    fun share(zip: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}${LauncherInstaller.AUTHORITY_SUFFIX}", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, zip.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(zip.name, uri) // lets the chooser preview it and pass the grant on
        val chooser = Intent.createChooser(send, "Share game assemblies")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            throw UserFacingException("No app on this device can share files", e)
        }
    }

    private fun packageInfo(pkg: String): PackageInfo? = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun readme(
        info: PackageInfo,
        apks: List<String>,
        files: List<Pair<String, ExtractedAssembly>>,
        hashes: List<String>,
        scanned: List<Pair<String, String>>,
        exportedAt: String,
    ): String = buildString {
        appendLine("Stardew Valley game assemblies, exported by ModHub")
        appendLine("===================================================")
        appendLine()
        appendLine("Compile references for building the ModHub Launcher (launcher/ in the ModHub repo).")
        appendLine("They come from your own installed copy of the game: do not redistribute them.")
        appendLine()
        appendLine("Package:      ${info.packageName}")
        appendLine("Version name: ${info.versionName}")
        appendLine("Version code: ${versionCode(info)}")
        appendLine("Exported at:  $exportedAt")
        appendLine("ModHub:       ${com.modhub.app.BuildConfig.VERSION_NAME}")
        appendLine()
        appendLine("APK files:")
        apks.forEach { p -> appendLine("  $p  [${scanned.firstOrNull { it.first == p }?.second ?: "not needed"}]") }
        appendLine()
        appendLine("Files:")
        files.forEachIndexed { i, (apk, a) ->
            appendLine("  ${a.fileName}")
            appendLine("    size:   ${a.bytes.size} bytes${if (a.wasCompressed) " (LZ4-decompressed)" else ""}")
            appendLine("    sha256: ${hashes[i]}")
            appendLine("    from:   $apk")
            appendLine("            ${a.entry} (${a.packaging.label})")
        }
    }

    private fun manifestJson(
        info: PackageInfo,
        files: List<Pair<String, ExtractedAssembly>>,
        hashes: List<String>,
        exportedAt: String,
    ): String = JSON.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("format", 1)
            put("packageName", info.packageName)
            put("versionName", info.versionName)
            put("versionCode", versionCode(info))
            put("exportedAt", exportedAt)
            put("exportedBy", "ModHub ${com.modhub.app.BuildConfig.VERSION_NAME}")
            putJsonArray("files") {
                files.forEachIndexed { i, (apk, a) ->
                    addJsonObject {
                        put("name", a.fileName)
                        put("size", a.bytes.size)
                        put("sha256", hashes[i])
                        put("apk", apk)
                        put("entry", a.entry)
                        put("packaging", a.packaging.name)
                        put("lz4Compressed", a.wasCompressed)
                    }
                }
            }
            putJsonArray("required") { REQUIRED_DLLS.forEach { add(it) } }
        },
    )

    private fun versionCode(info: PackageInfo): Long = PackageInfoCompat.getLongVersionCode(info)

    private fun ZipOutputStream.entry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun key(fileName: String) = fileName.lowercase().removeSuffix(".dll")
    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        private const val TAG = "GameAssemblyExporter"
        const val CACHE_DIR = "devtools"
        const val ZIP_PREFIX = "game-assemblies-"
        val REQUIRED_DLLS = listOf("StardewValley.dll", "StardewValley.GameData.dll", "MonoGame.Framework.dll")

        /** Package -> store, in the order offered in Settings. Same packages as the launcher's StardewApkTool. */
        val GAME_PACKAGES = listOf(
            "com.chucklefish.stardewvalley" to "Google Play",
            "com.chucklefish.stardewvalleysamsung" to "Galaxy Store",
        )
        private val JSON = Json { prettyPrint = true }
    }
}
