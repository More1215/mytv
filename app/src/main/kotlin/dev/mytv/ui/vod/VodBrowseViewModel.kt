package dev.mytv.ui.vod

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.mytv.domain.model.Category
import dev.mytv.domain.model.ContentKey
import dev.mytv.domain.model.SyncState
import dev.mytv.domain.result.AppResult
import dev.mytv.domain.usecase.ObserveActivePortalUseCase
import dev.mytv.domain.usecase.ObserveMoviesUseCase
import dev.mytv.domain.usecase.ObserveResumeUseCase
import dev.mytv.domain.usecase.ObserveSeriesUseCase
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Élément de grille commun aux films et aux séries. */
data class PosterItem(
    val id: Long,
    val title: String,
    val imageUrl: String?,
    /** Avancement de lecture (films uniquement), null si jamais commencé. */
    val progress: Float?,
)

data class PosterGrid(val categoryId: String, val items: List<PosterItem>?)

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
    private val observeMovies: ObserveMoviesUseCase,
    private val observeSeries: ObserveSeriesUseCase,
    private val observeResume: ObserveResumeUseCase,
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

    val grid: StateFlow<PosterGrid?> = combine(portalId.filterNotNull(), selectedCategoryId) { id, cat -> id to cat }
        .flatMapLatest { (id, cat) -> itemsFor(id, cat).map { PosterGrid(cat, it) } }
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

    private fun itemsFor(portalId: Long, categoryId: String): Flow<List<PosterItem>> = when (kind) {
        VodKind.MOVIES -> observeMovies(portalId, categoryId).flatMapLatest { movies ->
            // Progression de lecture affichée sur les affiches des films déjà commencés.
            val keys = movies.map { ContentKey.MovieKey(it.streamId).storageKey }
            val resumes = if (keys.isEmpty()) flowOf(emptyMap()) else observeResume.many(portalId, keys)
            resumes.map { points ->
                movies.map { m ->
                    PosterItem(m.streamId, m.name, m.posterUrl, points[ContentKey.MovieKey(m.streamId).storageKey]?.progress)
                }
            }
        }
        VodKind.SERIES -> observeSeries(portalId, categoryId).map { list ->
            list.map { PosterItem(it.seriesId, it.name, it.coverUrl, null) }
        }
    }

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
