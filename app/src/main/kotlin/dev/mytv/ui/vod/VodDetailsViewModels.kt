package dev.mytv.ui.vod

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.mytv.domain.model.ContentKey
import dev.mytv.domain.model.Episode
import dev.mytv.domain.model.MovieDetails
import dev.mytv.domain.model.ResumePoint
import dev.mytv.domain.model.SeriesDetails
import dev.mytv.domain.result.AppError
import dev.mytv.domain.result.AppResult
import dev.mytv.domain.usecase.GetMovieDetailsUseCase
import dev.mytv.domain.usecase.GetSeriesDetailsUseCase
import dev.mytv.domain.usecase.ObserveActivePortalUseCase
import dev.mytv.domain.usecase.ObserveResumeUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Chargement d'une fiche : en cours, prête, ou en erreur (avec réessai possible). */
sealed interface DetailsState<out T> {
    data object Loading : DetailsState<Nothing>
    data class Ready<T>(val value: T) : DetailsState<T>
    data class Failed(val error: AppError) : DetailsState<Nothing>
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MovieDetailsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val observeActivePortal: ObserveActivePortalUseCase,
    private val getDetails: GetMovieDetailsUseCase,
    observeResume: ObserveResumeUseCase,
) : ViewModel() {

    private val streamId: Long = checkNotNull(savedState.get<String>("id")).toLong()

    private val _state = MutableStateFlow<DetailsState<MovieDetails>>(DetailsState.Loading)
    val state: StateFlow<DetailsState<MovieDetails>> = _state.asStateFlow()

    /** Position enregistrée : se met à jour au retour du lecteur. */
    val resume: StateFlow<ResumePoint?> = observeActivePortal().filterNotNull()
        .flatMapLatest { observeResume.one(it.id, ContentKey.MovieKey(streamId)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = DetailsState.Loading
            val portalId = observeActivePortal().filterNotNull().first().id
            _state.value = when (val r = getDetails(portalId, streamId)) {
                is AppResult.Success -> DetailsState.Ready(r.value)
                is AppResult.Failure -> DetailsState.Failed(r.error)
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SeriesDetailsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val observeActivePortal: ObserveActivePortalUseCase,
    private val getDetails: GetSeriesDetailsUseCase,
    observeResume: ObserveResumeUseCase,
) : ViewModel() {

    private val seriesId: Long = checkNotNull(savedState.get<String>("id")).toLong()

    private val _state = MutableStateFlow<DetailsState<SeriesDetails>>(DetailsState.Loading)
    val state: StateFlow<DetailsState<SeriesDetails>> = _state.asStateFlow()

    private val _selectedSeason = MutableStateFlow<Int?>(null)
    val selectedSeason: StateFlow<Int?> = _selectedSeason.asStateFlow()

    /** Positions de tous les épisodes de la série (progression affichée sur chaque épisode). */
    val resumes: StateFlow<Map<String, ResumePoint>> = _state
        .map { (it as? DetailsState.Ready)?.value }
        .flatMapLatest { details ->
            if (details == null) {
                flowOf(emptyMap())
            } else {
                val keys = details.seasons.flatMap { s -> s.episodes.map { ContentKey.EpisodeKey(it.id).storageKey } }
                val portalId = observeActivePortal().filterNotNull().first().id
                observeResume.many(portalId, keys)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = DetailsState.Loading
            val portalId = observeActivePortal().filterNotNull().first().id
            _state.value = when (val r = getDetails(portalId, seriesId)) {
                is AppResult.Success -> DetailsState.Ready(r.value).also { ready ->
                    if (_selectedSeason.value == null) _selectedSeason.value = ready.value.seasons.firstOrNull()?.number
                }
                is AppResult.Failure -> DetailsState.Failed(r.error)
            }
        }
    }

    /** true dès que l'utilisateur a lui-même choisi une saison : on ne la change plus pour lui. */
    private var seasonPickedByUser = false

    fun selectSeason(number: Int) {
        seasonPickedByUser = true
        _selectedSeason.value = number
    }

    /** Ouvre la fiche sur la saison de l'épisode à reprendre (sauf si l'utilisateur a déjà choisi). */
    fun suggestSeason(number: Int) {
        if (!seasonPickedByUser) _selectedSeason.value = number
    }

    /**
     * Épisode proposé par le bouton principal : le dernier épisode commencé (le plus récent),
     * sinon le premier épisode de la série.
     */
    fun mainEpisode(details: SeriesDetails, resumes: Map<String, ResumePoint>): Pair<Episode, ResumePoint?>? {
        val all = details.seasons.flatMap { it.episodes }
        val latest = all.mapNotNull { ep -> resumes[ContentKey.EpisodeKey(ep.id).storageKey]?.let { ep to it } }
            .maxByOrNull { it.second.updatedAtMs }
        return latest ?: all.firstOrNull()?.let { it to null }
    }
}
