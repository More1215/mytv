package dev.mytv.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.mytv.backup.LocalBackupServer
import dev.mytv.domain.result.AppResult
import dev.mytv.domain.usecase.BackupUseCase
import dev.mytv.ui.common.tryRequestFocus
import dev.mytv.ui.theme.MyTvColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

data class BackupUiState(
    val address: String? = null,
    val pin: String? = null,
    val error: String? = null,
    val events: List<String> = emptyList(),
    val restored: Boolean = false,
)

/**
 * Pilote le serveur local de sauvegarde. Le serveur vit sur son propre thread et appelle ces
 * callbacks de façon bloquante (runBlocking), ce qui est acceptable : un seul échange à la fois,
 * jamais sur le thread principal.
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backup: BackupUseCase,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    private var server: LocalBackupServer? = null

    fun start() {
        if (server != null) return
        val s = LocalBackupServer(
            onExport = { pass -> runBlocking { (backup.export(pass) as? AppResult.Success)?.value } },
            onRestore = { content, pass ->
                runBlocking {
                    when (val r = backup.restore(content, pass)) {
                        is AppResult.Success -> {
                            val sum = r.value
                            val text = "Restauration réussie : ${sum.portals} portail(s), ${sum.favorites} favori(s), ${sum.resumePoints} reprise(s)."
                            _state.update { it.copy(restored = true) }
                            addEvent(text)
                            text
                        }
                        is AppResult.Failure -> "Échec : phrase secrète incorrecte ou fichier qui n'est pas une sauvegarde MyTV.".also(::addEvent)
                    }
                }
            },
            onEvent = ::addEvent,
        )
        val port = s.start()
        val ip = LocalBackupServer.localAddress(context)
        if (port == null || ip == null) {
            _state.update { it.copy(error = "Impossible d'ouvrir le partage : la TV doit être connectée au réseau local (Wi-Fi ou Ethernet).") }
            s.stop()
            return
        }
        server = s
        _state.update { it.copy(address = "http://$ip:$port/?pin=${s.pin}", pin = s.pin, error = null) }
    }

    fun stop() {
        server?.stop()
        server = null
    }

    private fun addEvent(text: String) = _state.update { it.copy(events = (it.events + text).takeLast(4)) }

    override fun onCleared() = stop()
}

@Composable
fun BackupScreen(
    /** Appelé en quittant l'écran ; [restored] indique si une restauration a eu lieu. */
    onDone: (restored: Boolean) -> Unit,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val doneFocus = remember { FocusRequester() }
    // Le partage n'existe que pendant que cet écran est affiché.
    DisposableEffect(Unit) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }
    LaunchedEffect(Unit) { doneFocus.tryRequestFocus() }

    Page("Sauvegarde et restauration") {
        if (state.error != null) {
            Text(state.error!!, color = MyTvColors.Error, style = MaterialTheme.typography.titleMedium)
        } else {
            Text(
                "Sur ton PC ou ton téléphone, connecté au même réseau que la TV, ouvre cette adresse :",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                state.address ?: "…",
                style = MaterialTheme.typography.headlineSmall,
                color = MyTvColors.Accent,
                modifier = Modifier
                    .background(MyTvColors.Surface, RoundedCornerShape(10.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
            Text("Code de sécurité (déjà inclus dans l'adresse) : ${state.pin ?: "…"}", color = MyTvColors.OnSurfaceMuted)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("• Télécharger : choisis une phrase secrète, le fichier est chiffré avec elle.", color = MyTvColors.OnSurfaceMuted)
                Text("• Restaurer : envoie un fichier de sauvegarde avec sa phrase secrète.", color = MyTvColors.OnSurfaceMuted)
                Text("• Le partage s'arrête dès que tu quittes cet écran.", color = MyTvColors.OnSurfaceMuted)
            }
        }
        state.events.forEach { Text("✓ $it", style = MaterialTheme.typography.bodyLarge, color = MyTvColors.Success) }
        Button(onClick = { onDone(state.restored) }, modifier = Modifier.focusRequester(doneFocus)) {
            Text(if (state.restored) "Terminé – revenir à l'accueil" else "Fermer")
        }
    }
}
