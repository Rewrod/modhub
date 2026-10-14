package com.modhub.app.ui.screens.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.modhub.app.domain.ActiveDownload
import com.modhub.app.domain.DownloadManager
import com.modhub.app.domain.ModRepository
import com.modhub.app.domain.describe
import com.modhub.app.ui.navigation.Routes
import com.modhub.core.model.GameId
import com.modhub.core.model.ModFile
import com.modhub.core.model.ModFileCategory
import com.modhub.core.model.ModInfo
import com.modhub.core.source.ModListing
import com.modhub.core.source.ModSource
import com.modhub.nexus.NexusModSource
import com.modhub.nexus.RateLimitInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FilesSheet(
    val mod: ModInfo,
    val loading: Boolean = true,
    val files: List<ModFile> = emptyList(),
    val error: String? = null,
)

data class BrowseUiState(
    val listing: ModListing = ModListing.LATEST_UPDATED,
    val loading: Boolean = false,
    /** Unfiltered list for [listing]. */
    val mods: List<ModInfo> = emptyList(),
    val error: String? = null,
    /** Null until checked; false means "no API key". */
    val configured: Boolean? = null,
    val query: String = "",
    val sheet: FilesSheet? = null,
) {
    val visibleMods: List<ModInfo>
        get() {
            val q = query.trim()
            if (q.isEmpty()) return mods
            return mods.filter { m ->
                m.name.contains(q, ignoreCase = true) ||
                    m.summary.contains(q, ignoreCase = true) ||
                    m.author.contains(q, ignoreCase = true)
            }
        }
}

@HiltViewModel
class BrowseViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: ModRepository,
    private val downloadManager: DownloadManager,
) : ViewModel() {

    val gameId = GameId(savedState.get<String>(Routes.ARG_GAME_ID)?.takeIf { it.isNotBlank() } ?: "unknown")
    val gameName: String = repository.plugin(gameId)?.displayName ?: gameId.value

    /** First remote source for the game (Nexus today). */
    private val source: ModSource? = repository.remoteSourcesFor(gameId).firstOrNull()
    val sourceName: String = source?.displayName ?: "No source"
    val searchNote: String = if (source is NexusModSource) NexusModSource.SEARCH_NOTE else "Filters the mods listed below."

    private val _state = MutableStateFlow(BrowseUiState())
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    val rateLimit: StateFlow<RateLimitInfo?> = (source as? NexusModSource)?.rateLimit
        ?: MutableStateFlow<RateLimitInfo?>(null).asStateFlow()

    val downloads: StateFlow<List<ActiveDownload>> = downloadManager.downloads
        .map { list -> list.filter { it.gameId == gameId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Cache per listing so switching tabs doesn't spend API quota. */
    private val cache = mutableMapOf<ModListing, List<ModInfo>>()
    private var loadJob: Job? = null
    private var filesJob: Job? = null

    /** Called from the screen's ON_RESUME: first load, and again when returning from Settings. */
    fun checkConfiguredAndLoad() {
        viewModelScope.launch {
            val configured = source?.let { runCatching { it.isConfigured() }.getOrDefault(false) } ?: false
            val was = _state.value.configured
            _state.update { it.copy(configured = configured) }
            if (configured && (was != true || _state.value.mods.isEmpty())) load(_state.value.listing, force = was == false)
        }
    }

    fun selectListing(listing: ModListing) {
        if (listing == _state.value.listing && _state.value.mods.isNotEmpty()) return
        load(listing)
    }

    fun reload() = load(_state.value.listing, force = true)

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    private fun load(listing: ModListing, force: Boolean = false) {
        val src = source ?: run {
            _state.update { it.copy(error = "No mod source is available for $gameName.") }
            return
        }
        val ref = repository.gameRef(gameId, src) ?: return
        if (!force && listing == _state.value.listing && loadJob?.isActive == true) return
        val cached = cache[listing]
        if (!force && cached != null) {
            _state.update { it.copy(listing = listing, mods = cached, error = null, loading = false) }
            return
        }
        loadJob?.cancel()
        _state.update { it.copy(listing = listing, loading = true, error = null, mods = cached ?: emptyList()) }
        loadJob = viewModelScope.launch {
            try {
                val mods = src.listLatest(ref, listing)
                cache[listing] = mods
                _state.update { if (it.listing == listing) it.copy(mods = mods, loading = false) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { if (it.listing == listing) it.copy(loading = false, error = describe(e)) else it }
            }
        }
    }

    fun openFiles(mod: ModInfo) {
        val src = source ?: return
        val ref = repository.gameRef(gameId, src) ?: return
        filesJob?.cancel()
        _state.update { it.copy(sheet = FilesSheet(mod)) }
        filesJob = viewModelScope.launch {
            try {
                val files = src.getFiles(ref, mod.id)
                    .filter { it.category != ModFileCategory.ARCHIVED }
                    .sortedWith(compareBy<ModFile>({ categoryRank(it.category) }, { -(it.uploadedAt ?: 0L) }))
                _state.update { s -> s.sheet?.takeIf { it.mod.id == mod.id }?.let { s.copy(sheet = it.copy(loading = false, files = files)) } ?: s }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { s -> s.sheet?.takeIf { it.mod.id == mod.id }?.let { s.copy(sheet = it.copy(loading = false, error = describe(e))) } ?: s }
            }
        }
    }

    fun closeFiles() {
        filesJob?.cancel()
        _state.update { it.copy(sheet = null) }
    }

    fun downloadAndInstall(mod: ModInfo, file: ModFile) {
        runCatching { repository.downloadAndInstall(gameId, file, mod) }
            .onSuccess { _messages.trySend("Downloading ${file.name}...") }
            .onFailure { _messages.trySend(describe(it)) }
        closeFiles()
    }

    fun cancelDownload(id: String) = downloadManager.cancel(id)
    fun dismissDownload(id: String) = downloadManager.dismiss(id)

    private fun categoryRank(c: ModFileCategory): Int = when (c) {
        ModFileCategory.MAIN -> 0
        ModFileCategory.UPDATE -> 1
        ModFileCategory.OPTIONAL -> 2
        ModFileCategory.MISCELLANEOUS -> 3
        ModFileCategory.UNKNOWN -> 4
        ModFileCategory.OLD_VERSION -> 5
        ModFileCategory.ARCHIVED -> 6
    }
}
