package dev.mytv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.mytv.domain.model.SearchResults
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
 * Recherche films + séries dans le cache local : aucune requête réseau, résultats immédiats
 * même avec un catalogue de dizaines de milliers de titres. Insensible à la casse et aux accents.
 */
@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    observeActivePortal: ObserveActivePortalUseCase,
    private val search: SearchVodUseCase,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** null = rien de cherché encore (moins de 2 caractères). */
    val results: StateFlow<SearchResults?> = combine(
        observeActivePortal().filterNotNull(),
        // Attente de 300 ms après la dernière lettre : pas une requête par touche du clavier.
        _query.debounce(300).distinctUntilChanged(),
    ) { portal, q -> portal.id to q.trim() }
        .mapLatest { (portalId, q) -> if (q.length < 2) null else search(portalId, q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun onQueryChange(value: String) {
        _query.value = value
    }
}
