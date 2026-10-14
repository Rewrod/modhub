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
import com.modhub.app.ui.navigation.Routes
import com.modhub.core.model.GameId
import com.modhub.core.model.InstalledMod
import dagger.hilt.android.lifecycle.HiltViewModel
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
import javax.inject.Inject

/** A snackbar message; [actionUrl] adds an "Open" action (e.g. the Nexus files tab). */
data class UiMessage(val text: String, val actionLabel: String? = null, val actionUrl: String? = null)

@HiltViewModel
class GameDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: ModRepository,
    private val downloadManager: DownloadManager,
) : ViewModel() {

    val gameId = GameId(savedState.get<String>(Routes.ARG_GAME_ID)?.takeIf { it.isNotBlank() } ?: "unknown")
    val gameName: String = repository.plugin(gameId)?.displayName ?: gameId.value
    val hasRemoteSources: Boolean = repository.remoteSourcesFor(gameId).isNotEmpty()

    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    /** Null until the first load finishes. */
    val snapshot: StateFlow<GameSnapshot?> = repository.observe(gameId, refreshes)
        .onEach { r -> _loadError.value = r.exceptionOrNull()?.let(::describe) }
        .filter { it.isSuccess }
        .map { it.getOrThrow() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val downloads: StateFlow<List<ActiveDownload>> = downloadManager.downloads
        .map { list -> list.filter { it.gameId == gameId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Locations of mods with an operation in flight (switch shows disabled). */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            downloadManager.events.filter { it.download.gameId == gameId }.collect { e ->
                when (e) {
                    is DownloadEvent.Installed -> {
                        val names = e.mods.joinToString { it.name }.ifEmpty { e.download.title }
                        _messages.send(UiMessage("Installed $names"))
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
