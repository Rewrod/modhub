package com.modhub.app.ui.screens.detail

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.modhub.app.domain.ActiveDownload
import com.modhub.app.domain.DownloadEvent
import com.modhub.app.domain.DownloadManager
import com.modhub.app.domain.ErrorKind
import com.modhub.app.domain.GameSnapshot
import com.modhub.app.domain.ModRepository
import com.modhub.app.domain.describe
import com.modhub.app.domain.kind
import com.modhub.app.platform.GameLauncher
import com.modhub.app.platform.LaunchTarget
import com.modhub.app.platform.LauncherInstaller
import com.modhub.app.ui.navigation.Routes
import com.modhub.core.model.GameId
import com.modhub.core.model.InstalledMod
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A snackbar message; [actionUrl] adds an "Open" action (e.g. the Nexus files tab). */
data class UiMessage(val text: String, val actionLabel: String? = null, val actionUrl: String? = null)

@HiltViewModel
class GameDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: ModRepository,
    private val downloadManager: DownloadManager,
    private val launcher: GameLauncher,
    private val launcherInstaller: LauncherInstaller,
) : ViewModel() {

    val gameId = GameId(savedState.get<String>(Routes.ARG_GAME_ID)?.takeIf { it.isNotBlank() } ?: "unknown")
    val gameName: String = repository.plugin(gameId)?.displayName ?: gameId.value
    val hasRemoteSources: Boolean = repository.remoteSourcesFor(gameId).isNotEmpty()

    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    /** Null until the first load finishes. */
    val snapshot: StateFlow<GameSnapshot?> = repository.observe(gameId, refreshes)
        .onEach { r ->
            _loadError.value = r.exceptionOrNull()?.let(::describe)
            // Detection re-ran (resume, refresh, install): the game or loader may have appeared.
            updateLaunchTarget()
        }
        .filter { it.isSuccess }
        .map { it.getOrThrow() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val downloads: StateFlow<List<ActiveDownload>> = downloadManager.downloads
        .map { list -> list.filter { it.gameId == gameId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** What "Play" starts: the mod loader if installed, else the plain game; null hides the button. */
    private val _launchTarget = MutableStateFlow<LaunchTarget?>(null)
    val launchTarget: StateFlow<LaunchTarget?> = _launchTarget.asStateFlow()

    /** True while one of this game's downloads/installs is still running ("Play" is disabled). */
    val installInProgress: StateFlow<Boolean> = downloads
        .map { list -> list.any { !it.isFinished } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Locations of mods with an operation in flight (switch shows disabled). */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    /** True while the launcher APK is downloading (the install button is disabled). */
    val launcherDownloadRunning: StateFlow<Boolean> = downloads
        .map { list -> list.any { it.title == LauncherInstaller.TITLE && !it.isFinished } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** "Not now" on the old-folder migration card (until the screen is left). */
    private val _migrationDismissed = MutableStateFlow(false)
    val migrationDismissed: StateFlow<Boolean> = _migrationDismissed.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            downloadManager.events.filter { it.download.gameId == gameId }.collect { e ->
                when (e) {
                    is DownloadEvent.Installed -> when {
                        e.download.title == LauncherInstaller.TITLE -> _messages.send(
                            UiMessage("Confirm the install in Android's dialog, then come back to ModHub"),
                        )
                        e.download.title == ModRepository.MIGRATION_TITLE -> _messages.send(
                            UiMessage(
                                if (e.mods.isEmpty()) "Nothing left to move" else "Moved ${e.mods.size} mods into ModHub Launcher",
                            ),
                        )
                        else -> {
                            val names = e.mods.joinToString { it.name }.ifEmpty { e.download.title }
                            _messages.send(UiMessage("Installed $names"))
                        }
                    }
                    is DownloadEvent.Failed -> {
                        val err = e.download.error ?: return@collect
                        val premium = err.kind() == ErrorKind.PREMIUM_REQUIRED
                        _messages.send(
                            UiMessage(
                                text = if (premium) "Direct download needs Nexus Premium. Use \"Mod Manager Download\" on the mod's Files tab."
                                else "${e.download.title}: ${describe(err)}",
                                actionLabel = if (e.download.helpUrl != null) "Open" else null,
                                actionUrl = e.download.helpUrl,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun refresh() {
        refreshes.tryEmit(Unit)
        viewModelScope.launch { updateLaunchTarget() }
    }

    /** Starts the game (through its loader when available). Refused while an install is running. */
    fun onPlay() {
        if (downloadManager.downloads.value.any { it.gameId == gameId && !it.isFinished }) {
            _messages.trySend(UiMessage("Finish installing first"))
            return
        }
        viewModelScope.launch {
            val target = _launchTarget.value ?: updateLaunchTarget()
            if (target == null || !launcher.launch(target)) {
                _messages.send(UiMessage("Couldn't start ${gameName}. Is it still installed?"))
                updateLaunchTarget()
            }
        }
    }

    private suspend fun updateLaunchTarget(): LaunchTarget? {
        val plugin = repository.plugin(gameId) ?: return null.also { _launchTarget.value = null }
        val target = withContext(Dispatchers.IO) { runCatching { launcher.resolve(plugin) }.getOrNull() }
        _launchTarget.value = target
        return target
    }

    /** Downloads ModHub Launcher and opens the system installer (shown while the bridge is missing). */
    fun installLauncher() {
        runCatching { launcherInstaller.install(gameId) }
            .onFailure { _messages.trySend(UiMessage(describe(it))) }
    }

    /** Copies the old folder's mods into the launcher; [deleteOriginals] removes each copied original. */
    fun migrateLegacyMods(deleteOriginals: Boolean) {
        runCatching { repository.migrateLegacyMods(gameId, deleteOriginals) }
            .onFailure { _messages.trySend(UiMessage(describe(it))) }
    }

    fun dismissMigration() {
        _migrationDismissed.value = true
    }

    fun setEnabled(mod: InstalledMod, enabled: Boolean) = runOn(mod) {
        repository.setEnabled(mod, enabled)
    }

    fun uninstall(mod: InstalledMod) = runOn(mod) {
        repository.uninstall(mod)
        _messages.send(UiMessage("Removed ${mod.name}"))
    }

    fun installFromFile(uri: Uri) {
        runCatching { repository.installFromUri(gameId, uri) }
            .onFailure { _messages.trySend(UiMessage(describe(it))) }
    }

    fun useTreeAsModsFolder(uri: Uri) {
        viewModelScope.launch {
            runCatching { repository.useTreeAsModsFolder(gameId, uri) }
                .onSuccess { _messages.send(UiMessage("Mods folder set to $it")) }
                .onFailure { _messages.send(UiMessage(describe(it))) }
        }
    }

    fun clearModsFolder() {
        viewModelScope.launch {
            runCatching { repository.clearModsFolder(gameId) }
                .onFailure { _messages.send(UiMessage(describe(it))) }
        }
    }

    fun cancelDownload(id: String) = downloadManager.cancel(id)
    fun dismissDownload(id: String) = downloadManager.dismiss(id)

    private fun runOn(mod: InstalledMod, block: suspend () -> Unit) {
        val key = mod.location.value
        if (key in _busy.value) return
        _busy.value = _busy.value + key
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _messages.send(UiMessage(describe(e)))
            } finally {
                _busy.value = _busy.value - key
            }
        }
    }
}
