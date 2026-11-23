package dev.mytv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.mytv.domain.model.Portal
import dev.mytv.domain.model.PlayerSettings
import dev.mytv.domain.usecase.DeletePortalUseCase
import dev.mytv.domain.usecase.ObserveActivePortalUseCase
import dev.mytv.domain.usecase.ObservePlayerSettingsUseCase
import dev.mytv.domain.usecase.ObservePortalsUseCase
import dev.mytv.domain.usecase.SwitchPortalUseCase
import dev.mytv.domain.usecase.UpdatePlayerSettingsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import dev.mytv.domain.repository.NetworkProtection
import dev.mytv.domain.usecase.ObserveNetworkProtectionUseCase
import dev.mytv.domain.usecase.SetVpnRequiredUseCase
import javax.inject.Inject

data class PortalsUiState(
    val portals: List<Portal> = emptyList(),
    val activeId: Long? = null,
    /** Portail dont la suppression attend confirmation. */
    val confirmDelete: Portal? = null,
)

@HiltViewModel
class PortalsViewModel @Inject constructor(
    observePortals: ObservePortalsUseCase,
    observeActive: ObserveActivePortalUseCase,
    private val switchPortal: SwitchPortalUseCase,
    private val deletePortal: DeletePortalUseCase,
) : ViewModel() {

    private val confirm = MutableStateFlow<Portal?>(null)

    val state: StateFlow<PortalsUiState> = combine(observePortals(), observeActive(), confirm) { list, active, c ->
        PortalsUiState(list, active?.id, c)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PortalsUiState())

    /** Active le portail puis prévient l'écran (retour à l'accueil sur le nouveau portail). */
    fun activate(portal: Portal, then: () -> Unit) = viewModelScope.launch {
        switchPortal(portal.id)
        then()
    }

    fun askDelete(portal: Portal) { confirm.value = portal }
    fun cancelDelete() { confirm.value = null }

    /** [then] reçoit true s'il reste au moins un portail. */
    fun confirmDelete(then: (portalsLeft: Boolean) -> Unit) = viewModelScope.launch {
        val target = confirm.value ?: return@launch
        confirm.value = null
        deletePortal(target.id)
        then(state.value.portals.any { it.id != target.id })
    }
}

@HiltViewModel
class PlayerSettingsViewModel @Inject constructor(
    observeSettings: ObservePlayerSettingsUseCase,
    private val updateSettings: UpdatePlayerSettingsUseCase,
) : ViewModel() {
    val settings: StateFlow<PlayerSettings> = observeSettings()

    fun update(transform: (PlayerSettings) -> PlayerSettings) = viewModelScope.launch { updateSettings(transform) }
}

@HiltViewModel
class PrivacySettingsViewModel @Inject constructor(
    observeProtection: ObserveNetworkProtectionUseCase,
    private val setVpnRequired: SetVpnRequiredUseCase,
) : ViewModel() {
    val protection: StateFlow<NetworkProtection> = observeProtection()

    /**
     * Réexiger le VPN alors qu'aucun n'est actif bascule aussitôt sur l'écran « VPN requis » :
     * c'est le comportement attendu (même protection qu'au démarrage).
     */
    fun toggleRequired() = setVpnRequired(!protection.value.vpnRequired)
}

/** Valeur suivante d'une énumération, en boucle : OK fait défiler les choix d'un réglage. */
inline fun <reified E : Enum<E>> E.next(): E = enumValues<E>().let { it[(ordinal + 1) % it.size] }
