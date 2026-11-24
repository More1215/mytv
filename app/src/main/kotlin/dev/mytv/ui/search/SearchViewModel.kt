package dev.mytv.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.mytv.domain.model.Channel
import dev.mytv.domain.model.SearchResults
import dev.mytv.domain.usecase.SearchChannelsUseCase
import dev.mytv.domain.usecase.ObserveActivePortalUseCase
import dev.mytv.domain.usecase.SearchVodUseCase
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Recherche chaînes + films + séries dans le cache local : aucune requête réseau, résultats immédiats
 * même avec un catalogue de dizaines de milliers de titres. Insensible à la casse et aux accents.
 */
/** Résultats affichés : chaînes en premier, puis films et séries. */
data class SearchUi(val channels: List<Channel>, val vod: SearchResults) {
    val isEmpty: Boolean get() = channels.isEmpty() && vod.isEmpty
}

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    observeActivePortal: ObserveActivePortalUseCase,
    private val search: SearchVodUseCase,
    private val searchChannels: SearchChannelsUseCase,
    savedState: SavedStateHandle,
    private val recent: RecentChannelSearches,
) : ViewModel() {

    /** Ouverte depuis Live TV : chaînes seulement (pas de recherche films/séries, plus rapide). */
    val channelsOnly: Boolean = savedState.get<Boolean>("channels") ?: false

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** null = rien de cherché encore (moins de 2 caractères). */
    val results: StateFlow<SearchUi?> = combine(
        observeActivePortal().filterNotNull(),
        // Attente de 300 ms après la dernière lettre : pas une requête par touche du clavier.
        _query.debounce(300).distinctUntilChanged(),
    ) { portal, q -> portal.id to q.trim() }
        .mapLatest { (portalId, q) -> if (q.length < 2) null else SearchUi(searchChannels(portalId, q), if (channelsOnly) SearchResults(emptyList(), emptyList()) else search(portalId, q)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val portalId = observeActivePortal().filterNotNull()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Chaîne ouverte depuis la recherche : elle apparaîtra dans « Dernières recherches ». */
    fun onChannelChosen(channel: Channel) {
        portalId.value?.let { recent.add(it.id, channel.streamId) }
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }
}
