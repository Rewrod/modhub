package com.modhub.app.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.modhub.app.BuildConfig
import com.modhub.app.domain.DownloadManager
import com.modhub.app.domain.DownloadReporter
import com.modhub.app.domain.UserFacingException
import com.modhub.app.storage.BridgeClient
import com.modhub.core.model.GameId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads ModHub Launcher (our SMAPILoader fork with the bridge, `launcher/` in this repo)
 * and hands it to the system package installer.
 *
 * It keeps the upstream package name and signing key, so it installs as an update over the
 * official SMAPI Launcher and keeps its data (SMAPI, saves). The APK goes to
 * `cacheDir/launcher/` and is shared through the app's FileProvider (`res/xml/file_paths.xml`).
 */
@Singleton
class LauncherInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttp: OkHttpClient,
    private val downloads: DownloadManager,
    private val bridge: BridgeClient,
) {
    /** Where the APK comes from; set with the `modhub.launcherApkUrl` Gradle property. */
    val apkUrl: String get() = BuildConfig.LAUNCHER_APK_URL

    /** Starts the download as a [DownloadManager] job for [gameId]'s screen. Returns the job id. */
    fun install(gameId: GameId): String =
        downloads.enqueue(gameId, title = TITLE) { reporter ->
            val apk = download(reporter)
            reporter.installing(detail = "Opening the installer")
            openInstaller(apk)
            // The launcher's bridge appears once the user confirms; detection re-reads it on resume.
            bridge.invalidate()
            emptyList()
        }

    private suspend fun download(reporter: DownloadReporter): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val part = File(dir, "$APK_NAME.part")
        val apk = File(dir, APK_NAME)
        reporter.downloading(0, null)
        val call = okHttp.newCall(Request.Builder().url(apkUrl).build())
        val job = coroutineContext[Job]
        val cancelHook = job?.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw UserFacingException("Couldn't download ModHub Launcher (HTTP ${response.code}) from $apkUrl")
                }
                val body = response.body ?: throw UserFacingException("Empty response downloading ModHub Launcher")
                val total = body.contentLength().takeIf { it > 0 }
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(BUFFER_SIZE)
                        var read = 0L
                        var lastReport = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            read += n
                            if (read - lastReport >= REPORT_STEP || read == total) {
                                lastReport = read
                                reporter.downloading(read, total)
                            }
                        }
                        reporter.downloading(read, total ?: read)
                    }
                }
            }
            if (apk.exists()) apk.delete()
            if (!part.renameTo(apk)) throw IOException("Couldn't save $APK_NAME")
            apk
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            cancelHook?.dispose()
        }
    }

    /**
     * Opens the system installer. Android asks the user to allow "Install unknown apps" for
     * ModHub the first time (REQUEST_INSTALL_PACKAGES is declared in the manifest).
     */
    private fun openInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw UserFacingException("No app on this device can install APKs", e)
        }
    }

    companion object {
        const val TITLE = "ModHub Launcher"
        const val APK_NAME = "ModHub-Launcher.apk"
        const val CACHE_DIR = "launcher"
        const val APK_MIME = "application/vnd.android.package-archive"
        /** Must match the FileProvider `android:authorities` in AndroidManifest.xml. */
        const val AUTHORITY_SUFFIX = ".fileprovider"
        private const val BUFFER_SIZE = 64 * 1024
        private const val REPORT_STEP = 256L * 1024
    }
}
