package dev.mytv.ui.vod

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dev.mytv.domain.model.Category
import dev.mytv.domain.model.PosterEntry
import dev.mytv.domain.model.SyncState
import dev.mytv.domain.result.AppResult
import dev.mytv.domain.usecase.ObserveActivePortalUseCase
import dev.mytv.domain.usecase.ObservePosterGridUseCase
import dev.mytv.domain.usecase.ObserveVodCategoriesUseCase
import dev.mytv.domain.usecase.SyncVodUseCase
import dev.mytv.domain.usecase.VodKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Navigation dans les films OU les séries (selon l'argument de route "kind") :
 * catégories à gauche, grille d'affiches à droite. Même logique que la Live TV.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class VodBrowseViewModel @Inject constructor(
    savedState: SavedStateHandle,
    observeActivePortal: ObserveActivePortalUseCase,
    observeCategories: ObserveVodCategoriesUseCase,
    private val posterGrid: ObservePosterGridUseCase,
    private val syncVod: SyncVodUseCase,
) : ViewModel() {

    val kind: VodKind = if (savedState.get<String>("kind") == "series") VodKind.SERIES else VodKind.MOVIES

    private val portalId = observeActivePortal().map { it?.id }.filterNotNull()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val categories: StateFlow<List<Category>> = portalId.filterNotNull()
        .flatMapLatest { observeCategories(it, kind) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val focusedCategory = MutableStateFlow(Category.ALL_ID)

    /** Catégorie affichée, avec un léger délai pour ne pas recharger à chaque catégorie survolée. */
    val selectedCategoryId: StateFlow<String> = focusedCategory.debounce(250).distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, Category.ALL_ID)

    private val gridKey = combine(portalId.filterNotNull(), selectedCategoryId) { id, cat -> id to cat }

    /** Grille paginée : la base ne lit que les affiches proches de l'écran. */
    val pages: Flow<PagingData<PosterEntry>> = gridKey
        .flatMapLatest { (id, cat) -> posterGrid.pages(id, kind, cat) }
        .cachedIn(viewModelScope)

    /** Nombre total dans la catégorie (null tant qu'inconnu). */
    val count: StateFlow<Int?> = gridKey
        .flatMapLatest { (id, cat) -> posterGrid.count(id, kind, cat) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _sync = MutableStateFlow<SyncState>(SyncState.Idle)
    val sync: StateFlow<SyncState> = _sync.asStateFlow()

    init {
        viewModelScope.launch { sync(force = false) }
    }

    fun onCategoryFocused(categoryId: String) {
        focusedCategory.value = categoryId
    }

    fun refresh() = viewModelScope.launch { sync(force = true) }

    private suspend fun sync(force: Boolean) {
        if (_sync.value == SyncState.Running) return
        val id = portalId.filterNotNull().first()
        _sync.value = SyncState.Running
        _sync.value = when (val r = syncVod(id, force)) {
            is AppResult.Success -> SyncState.Idle
            is AppResult.Failure -> SyncState.Failed(r.error)
        }
    }
}
