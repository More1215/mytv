package dev.mytv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.mytv.domain.model.Channel
import dev.mytv.domain.model.ResumePoint
import dev.mytv.domain.usecase.ChannelStatsUseCase
import dev.mytv.domain.usecase.ObserveActivePortalUseCase
import dev.mytv.domain.usecase.ObserveNetworkProtectionUseCase
import dev.mytv.domain.usecase.ObserveResumeUseCase
import dev.mytv.domain.usecase.SyncLiveUseCase
import dev.mytv.domain.usecase.SyncVodUseCase
import dev.mytv.ui.live.PrivacyState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Accueil : accès aux sections et rangée « Continuer à regarder ».
 * Lance aussi en arrière-plan les synchros Live puis VOD si elles sont périmées, pour que chaque
 * section s'ouvre ensuite instantanément depuis le cache.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    observeActivePortal: ObserveActivePortalUseCase,
    private val observeResume: ObserveResumeUseCase,
    private val channelStats: ChannelStatsUseCase,
    observeProtection: ObserveNetworkProtectionUseCase,
    private val syncLive: SyncLiveUseCase,
    private val syncVod: SyncVodUseCase,
) : ViewModel() {

    private val portal = observeActivePortal().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val continueWatching: StateFlow<List<ResumePoint>> = portal.filterNotNull()
        .flatMapLatest { observeResume.continueWatching(it.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Les 2 chaînes les plus regardées (requête limitée à 2 lignes : rien de plus en mémoire). */
    val topChannels: StateFlow<List<Channel>> = portal.filterNotNull()
        .flatMapLatest { channelStats.mostWatched(it.id, limit = 2) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val privacy: StateFlow<PrivacyState> = combine(observeProtection(), portal) { p, portal ->
        PrivacyState(p.vpnActive, p.vpnRequired, portal?.isEncrypted == true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PrivacyState(false, true, false))

    init {
        viewModelScope.launch {
            val id = portal.filterNotNull().first().id
            syncLive(id)
            syncVod(id)
        }
    }

    /** Appui long dans « Accès rapide » : retire la chaîne (compteur à zéro). */
    fun removeChannel(channel: Channel) = viewModelScope.launch {
        val id = portal.value?.id ?: return@launch
        channelStats.forget(id, channel.streamId)
    }

    /** Appui long sur une reprise : l'efface (elle disparaît aussi de « Continuer à regarder »). */
    fun removeResume(point: ResumePoint) = viewModelScope.launch {
        val id = portal.value?.id ?: return@launch
        observeResume.remove(id, point.key)
    }
}
