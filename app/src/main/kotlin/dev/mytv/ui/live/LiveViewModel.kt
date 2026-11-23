package dev.mytv.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.mytv.domain.model.Category
import dev.mytv.domain.model.Channel
import dev.mytv.domain.model.NowNext
import dev.mytv.domain.model.SyncState
import dev.mytv.domain.result.AppResult
import dev.mytv.domain.usecase.LastChannelUseCase
import dev.mytv.domain.usecase.ObserveActivePortalUseCase
import dev.mytv.domain.usecase.ObserveCategoriesUseCase
import dev.mytv.domain.usecase.ObserveChannelsUseCase
import dev.mytv.domain.usecase.ObserveNowNextUseCase
import dev.mytv.domain.usecase.SyncLiveUseCase
import dev.mytv.domain.usecase.ToggleFavoriteUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import dev.mytv.domain.usecase.ObserveNetworkProtectionUseCase
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PrivacyState(val vpnActive: Boolean, val vpnRequired: Boolean, val portalEncrypted: Boolean)

/** Liste de chaînes d'une catégorie ; null = pas encore chargée (≠ catégorie vide). */
data class ChannelListState(val categoryId: String, val channels: List<Channel>?)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LiveViewModel @Inject constructor(
    observeActivePortal: ObserveActivePortalUseCase,
    observeCategories: ObserveCategoriesUseCase,
    private val observeChannels: ObserveChannelsUseCase,
    private val observeNowNext: ObserveNowNextUseCase,
    private val syncLive: SyncLiveUseCase,
    private val toggleFavorite: ToggleFavoriteUseCase,
    private val lastChannel: LastChannelUseCase,
    observeProtection: ObserveNetworkProtectionUseCase,
) : ViewModel() {

    private val portal = observeActivePortal()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val portalId: StateFlow<Long?> = portal
        .map { it?.id }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Ce que le FAI peut voir : affiché en permanence en haut de l'écran. */
    val privacy: StateFlow<PrivacyState> = combine(observeProtection(), portal) { protection, p ->
        PrivacyState(
            vpnActive = protection.vpnActive,
            vpnRequired = protection.vpnRequired,
            portalEncrypted = p?.isEncrypted == true,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PrivacyState(observeProtection().value.vpnActive, observeProtection().value.vpnRequired, false),
    )

    val categories: StateFlow<List<Category>> = portalId.filterNotNull()
        .flatMapLatest { observeCategories(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Catégorie survolée au D-pad. La liste suit avec un léger délai (voir [selectedCategoryId]). */
    private val focusedCategoryId = MutableStateFlow<String?>(null)

    /**
     * Catégorie réellement affichée. Le debounce évite de recharger la liste de chaînes à chaque
     * catégorie traversée quand on fait défiler la colonne rapidement.
     */
    val selectedCategoryId: StateFlow<String?> = focusedCategoryId
        .filterNotNull()
        .debounce(CATEGORY_DEBOUNCE_MS)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val channelList: StateFlow<ChannelListState?> = selectedCategoryId.filterNotNull()
        .flatMapLatest { categoryId ->
            portalId.filterNotNull().flatMapLatest { observeChannels(it, categoryId) }
                .map { ChannelListState(categoryId, it) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** « Now & next » des chaînes de la liste affichée, indexé par epgChannelId. */
    val nowNext: StateFlow<Map<String, NowNext>> = channelList
        .map { list -> list?.channels.orEmpty().mapNotNull { it.epgChannelId }.distinct() }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            val id = portalId.value
            if (ids.isEmpty() || id == null) flowOf(emptyMap()) else observeNowNext(id, ids)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _sync = MutableStateFlow<SyncState>(SyncState.Idle)
    val sync: StateFlow<SyncState> = _sync.asStateFlow()

    init {
        viewModelScope.launch {
            val id = portalId.filterNotNull().first()
            // Catégorie initiale : celle de la dernière chaîne regardée, sinon « Toutes ».
            if (focusedCategoryId.value == null) {
                focusedCategoryId.value = lastChannel.get(id)?.second ?: Category.ALL_ID
            }
            sync(force = false)
        }
    }

    fun onCategoryFocused(categoryId: String) {
        focusedCategoryId.value = categoryId
    }

    fun refresh() = viewModelScope.launch { sync(force = true) }

    fun toggleFavorite(channel: Channel) = viewModelScope.launch {
        portalId.value?.let { toggleFavorite(it, channel.streamId) }
    }

    private suspend fun sync(force: Boolean) {
        if (_sync.value == SyncState.Running) return
        val id = portalId.filterNotNull().first()
        _sync.value = SyncState.Running
        _sync.value = when (val result = syncLive(id, force)) {
            is AppResult.Success -> SyncState.Idle
            is AppResult.Failure -> SyncState.Failed(result.error)
        }
    }

    private companion object {
        const val CATEGORY_DEBOUNCE_MS = 250L
    }
}
