package com.modhub.app.domain

import com.modhub.core.model.GameId
import com.modhub.core.model.InstalledMod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** One download/install job as the UI sees it. */
data class ActiveDownload(
    val id: String,
    val gameId: GameId,
    val title: String,
    val state: State = State.PREPARING,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
    val error: Throwable? = null,
    /** Web page to send the user to if the download needs a manual step (e.g. Nexus files tab). */
    val helpUrl: String? = null,
    /** Files written so far while [State.INSTALLING]. */
    val installDone: Int = 0,
    /** Files to write while [State.INSTALLING]; 0 = not known yet. */
    val installTotal: Int = 0,
    /** Optional short label for the current install step (e.g. a phase or file name). */
    val installDetail: String? = null,
    /** When the job entered [State.INSTALLING] (epoch ms), for "this is taking a while" hints. */
    val installStartedAt: Long? = null,
) {
    enum class State { PREPARING, DOWNLOADING, INSTALLING, DONE, FAILED, CANCELLED }

    /**
     * Progress 0..1 for the current phase, or null when indeterminate. While installing this is
     * files written / total files (never the finished download's fraction).
     */
    val fraction: Float? get() = when (state) {
        State.INSTALLING -> installTotal.takeIf { it > 0 }?.let { (installDone.toFloat() / it).coerceIn(0f, 1f) }
        else -> totalBytes?.takeIf { it > 0 }?.let { (bytesRead.toFloat() / it).coerceIn(0f, 1f) }
    }
    val isFinished: Boolean get() = state == State.DONE || state == State.FAILED || state == State.CANCELLED
}

/** One-shot outcomes, for snackbars/dialogs. */
sealed interface DownloadEvent {
    val download: ActiveDownload

    data class Installed(override val download: ActiveDownload, val mods: List<InstalledMod>) : DownloadEvent
    data class Failed(override val download: ActiveDownload) : DownloadEvent
}

/** Handle given to a job so it can report what it is doing. */
interface DownloadReporter {
    fun downloading(bytesRead: Long, totalBytes: Long?)
    /**
     * Switches to (or updates) the installing phase. [done]/[total] are file counts;
     * `total == 0` means unknown (indeterminate progress). Safe to call repeatedly.
     */
    fun installing(done: Int = 0, total: Int = 0, detail: String? = null)
    fun retitle(title: String)
    fun helpUrl(url: String?)
}

/**
 * In-app download queue: runs jobs in an app-wide scope (they survive screen changes, not
 * process death), exposes their state as a [StateFlow], and supports cancel. Finished jobs
 * stay listed until dismissed, except successful ones, which drop off after a few seconds.
 */
@Singleton
class DownloadManager @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _downloads = MutableStateFlow<List<ActiveDownload>>(emptyList())
    val downloads: StateFlow<List<ActiveDownload>> = _downloads.asStateFlow()

    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

    /** Starts [work]; it returns the mods it installed. Returns the download id. */
    fun enqueue(
        gameId: GameId,
        title: String,
        helpUrl: String? = null,
        work: suspend (DownloadReporter) -> List<InstalledMod>,
    ): String {
        val id = UUID.randomUUID().toString()
        _downloads.update { it + ActiveDownload(id = id, gameId = gameId, title = title, helpUrl = helpUrl) }
        val reporter = object : DownloadReporter {
            override fun downloading(bytesRead: Long, totalBytes: Long?) = edit(id) {
                it.copy(state = ActiveDownload.State.DOWNLOADING, bytesRead = bytesRead, totalBytes = totalBytes)
            }
            override fun installing(done: Int, total: Int, detail: String?) = edit(id) {
                if (it.isFinished) return@edit it
                it.copy(
                    state = ActiveDownload.State.INSTALLING,
                    installDone = done,
                    installTotal = total,
                    installDetail = detail,
                    installStartedAt = it.installStartedAt ?: System.currentTimeMillis(),
                )
            }
            override fun retitle(title: String) = edit(id) { it.copy(title = title) }
            override fun helpUrl(url: String?) = edit(id) { it.copy(helpUrl = url) }
        }
        jobs[id] = scope.launch {
            try {
                val mods = work(reporter)
                edit(id) { it.copy(state = ActiveDownload.State.DONE) }
                find(id)?.let { _events.tryEmit(DownloadEvent.Installed(it, mods)) }
                delay(DONE_LINGER_MS)
                remove(id)
            } catch (e: CancellationException) {
                edit(id) { it.copy(state = ActiveDownload.State.CANCELLED) }
                throw e
            } catch (e: Throwable) {
                edit(id) { it.copy(state = ActiveDownload.State.FAILED, error = e) }
                find(id)?.let { _events.tryEmit(DownloadEvent.Failed(it)) }
            } finally {
                jobs.remove(id)
            }
        }
        return id
    }

    fun cancel(id: String) {
        val job = jobs[id]
        if (job != null) {
            job.cancel()
        } else {
            edit(id) { if (it.isFinished) it else it.copy(state = ActiveDownload.State.CANCELLED) }
        }
    }

    /** Removes a finished entry from the list (cancels it first if still running). */
    fun dismiss(id: String) {
        jobs.remove(id)?.cancel()
        remove(id)
    }

    private fun find(id: String): ActiveDownload? = _downloads.value.firstOrNull { it.id == id }

    private fun edit(id: String, change: (ActiveDownload) -> ActiveDownload) =
        _downloads.update { list -> list.map { if (it.id == id) change(it) else it } }

    private fun remove(id: String) = _downloads.update { list -> list.filterNot { it.id == id } }

    private companion object {
        const val DONE_LINGER_MS = 4_000L
    }
}
