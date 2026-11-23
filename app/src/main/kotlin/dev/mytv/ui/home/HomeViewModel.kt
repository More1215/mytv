package dev.mytv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.mytv.domain.model.ResumePoint
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
    observeResume: ObserveResumeUseCase,
    observeProtection: ObserveNetworkProtectionUseCase,
    private val syncLive: SyncLiveUseCase,
    private val syncVod: SyncVodUseCase,
) : ViewModel() {

    private val portal = observeActivePortal().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val continueWatching: StateFlow<List<ResumePoint>> = portal.filterNotNull()
        .flatMapLatest { observeResume.continueWatching(it.id) }
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
}
