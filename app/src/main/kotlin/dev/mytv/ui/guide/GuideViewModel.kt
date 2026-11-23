package dev.mytv.ui.guide

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.mytv.domain.model.Category
import dev.mytv.domain.model.Channel
import dev.mytv.domain.model.EpgProgram
import dev.mytv.domain.usecase.ObserveActivePortalUseCase
import dev.mytv.domain.usecase.ObserveCategoriesUseCase
import dev.mytv.domain.usecase.ObserveChannelsUseCase
import dev.mytv.domain.usecase.ObserveGuideUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class GuideRow(val channel: Channel, val programs: List<EpgProgram>)

data class GuideUiState(
    val categoryName: String = "",
    /** Début de la fenêtre visible (arrondi à la demi-heure). */
    val windowStartMs: Long,
    val rows: List<GuideRow>? = null,
)

/**
 * Grille du guide façon Formuler : les chaînes de la catégorie courante en lignes, le temps en
 * colonnes. La fenêtre visible fait [WINDOW_MS] ; on la décale par pas de 30 min au D-pad.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GuideViewModel @Inject constructor(
    savedState: SavedStateHandle,
    observeActivePortal: ObserveActivePortalUseCase,
    observeCategories: ObserveCategoriesUseCase,
    observeChannels: ObserveChannelsUseCase,
    observeGuide: ObserveGuideUseCase,
) : ViewModel() {

    val categoryId: String = savedState["categoryId"] ?: Category.ALL_ID

    private val windowStart = MutableStateFlow(roundDownToHalfHour(System.currentTimeMillis()))
    val windowStartMs: StateFlow<Long> = windowStart.asStateFlow()

    private val portalId = observeActivePortal().map { it?.id }.filterNotNull()

    private val channels = portalId.flatMapLatest { observeChannels(it, categoryId) }

    private val categoryName = portalId.flatMapLatest { observeCategories(it) }
        .map { list -> list.firstOrNull { it.id == categoryId }?.name.orEmpty() }

    /**
     * On charge une plage plus large que la fenêtre visible (-2 h / +24 h, toute la donnée en
     * cache) : décaler la fenêtre ne relance alors aucune requête.
     */
    private val programs = combine(portalId, channels) { id, list -> id to list }
        .flatMapLatest { (id, list) ->
            val ids = list.mapNotNull { it.epgChannelId }
            if (ids.isEmpty()) {
                flowOf(emptyMap())
            } else {
                val now = System.currentTimeMillis()
                observeGuide(id, ids, now - 2 * HOUR_MS, now + 24 * HOUR_MS)
            }
        }

    val state: StateFlow<GuideUiState> = combine(categoryName, channels, programs, windowStart) { name, list, progs, start ->
        GuideUiState(
            categoryName = name,
            windowStartMs = start,
            rows = list.map { GuideRow(it, it.epgChannelId?.let(progs::get).orEmpty()) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuideUiState(windowStartMs = windowStart.value))

    /** Décale la fenêtre ; bornée aux données disponibles (-2 h / +24 h). */
    fun shiftWindow(steps: Int) {
        val now = System.currentTimeMillis()
        val min = roundDownToHalfHour(now - 2 * HOUR_MS)
        val max = roundDownToHalfHour(now + 24 * HOUR_MS - WINDOW_MS)
        windowStart.value = (windowStart.value + steps * HALF_HOUR_MS).coerceIn(min, max)
    }

    companion object {
        const val HALF_HOUR_MS = 30L * 60 * 1000
        const val HOUR_MS = 60L * 60 * 1000
        /** Largeur de la fenêtre visible : 2 h. */
        const val WINDOW_MS = 2 * HOUR_MS

        fun roundDownToHalfHour(ms: Long): Long = ms - Math.floorMod(ms, HALF_HOUR_MS)
    }
}
