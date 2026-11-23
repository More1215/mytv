package dev.mytv.ui.portal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.mytv.BuildConfig
import dev.mytv.domain.model.ConnectionCheck
import dev.mytv.domain.model.PortalCredentials
import dev.mytv.domain.result.AppError
import dev.mytv.domain.result.AppResult
import dev.mytv.domain.usecase.AddPortalUseCase
import dev.mytv.domain.usecase.TestPortalConnectionUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddPortalUiState(
    val name: String = "",
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val phase: Phase = Phase.Idle,
) {
    val isBusy: Boolean get() = phase is Phase.Testing || phase is Phase.Saving
    val canSubmit: Boolean get() = url.isNotBlank() && username.isNotBlank() && password.isNotEmpty() && !isBusy

    sealed interface Phase {
        data object Idle : Phase
        data object Testing : Phase
        data object Saving : Phase
        data class Tested(val check: ConnectionCheck) : Phase
        data class Failed(val error: AppError) : Phase
        /** Portail enregistré : l'écran doit naviguer vers l'accueil. */
        data object Saved : Phase
    }
}

@HiltViewModel
class AddPortalViewModel @Inject constructor(
    private val testConnection: TestPortalConnectionUseCase,
    private val addPortal: AddPortalUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(
        // En debug, pré-remplissage depuis local.properties pour ne pas tout retaper à la télécommande.
        // En release ces champs BuildConfig sont toujours vides.
        AddPortalUiState(
            url = BuildConfig.DEBUG_PORTAL_URL,
            username = BuildConfig.DEBUG_PORTAL_USER,
            password = BuildConfig.DEBUG_PORTAL_PASS,
        ),
    )
    val state: StateFlow<AddPortalUiState> = _state.asStateFlow()

    private var job: Job? = null

    // Toute modification de saisie invalide le résultat d'un test précédent.
    fun onNameChange(v: String) = _state.update { it.copy(name = v) }
    fun onUrlChange(v: String) = _state.update { it.copy(url = v, phase = AddPortalUiState.Phase.Idle) }
    fun onUsernameChange(v: String) = _state.update { it.copy(username = v, phase = AddPortalUiState.Phase.Idle) }
    fun onPasswordChange(v: String) = _state.update { it.copy(password = v, phase = AddPortalUiState.Phase.Idle) }

    fun test() = launchExclusive(AddPortalUiState.Phase.Testing) {
        when (val r = testConnection(credentials())) {
            is AppResult.Success -> AddPortalUiState.Phase.Tested(r.value)
            is AppResult.Failure -> AddPortalUiState.Phase.Failed(r.error)
        }
    }

    fun save() = launchExclusive(AddPortalUiState.Phase.Saving) {
        when (val r = addPortal(_state.value.name, credentials())) {
            is AppResult.Success -> AddPortalUiState.Phase.Saved
            is AppResult.Failure -> AddPortalUiState.Phase.Failed(r.error)
        }
    }

    private fun credentials() = with(_state.value) { PortalCredentials(url, username, password) }

    /** Une seule opération réseau à la fois (évite les doubles appuis sur OK). */
    private fun launchExclusive(busy: AddPortalUiState.Phase, block: suspend () -> AddPortalUiState.Phase) {
        if (_state.value.isBusy) return
        job?.cancel()
        _state.update { it.copy(phase = busy) }
        job = viewModelScope.launch {
            val result = block()
            _state.update { it.copy(phase = result) }
        }
    }
}
