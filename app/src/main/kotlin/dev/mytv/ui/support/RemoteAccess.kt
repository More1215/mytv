package dev.mytv.ui.support

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.mytv.remote.RemoteControl
import dev.mytv.remote.RemoteControl.Status
import dev.mytv.ui.common.tryRequestFocus
import dev.mytv.ui.settings.Page
import dev.mytv.ui.theme.MyTvColors
import javax.inject.Inject
import kotlinx.coroutines.flow.first

@HiltViewModel
class RemoteAccessViewModel @Inject constructor(val remote: RemoteControl) : ViewModel()

/**
 * Réglages → Support → Code d'accès (aussi accessible depuis l'ajout de portail, pour une box neuve).
 * Ouvrir l'écran démarre l'aide ; elle continue pendant qu'on navigue, jusqu'à « Arrêter ».
 */
@Composable
fun RemoteAccessScreen(onClose: () -> Unit, viewModel: RemoteAccessViewModel = hiltViewModel()) {
    val remote = viewModel.remote
    val state by remote.state.collectAsStateWithLifecycle()
    val stopFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        remote.start()
        stopFocus.tryRequestFocus()
        // L'aide s'arrête (bouton, ou l'aidant a cliqué « Terminer ») : l'écran du code se ferme tout seul.
        remote.state.first { it.status == Status.OFF }
        onClose()
    }
    Page("Aide à distance") {
        Text(
            state.code ?: "…",
            style = MaterialTheme.typography.displaySmall,
            color = MyTvColors.Accent,
            modifier = Modifier
                .background(MyTvColors.Surface, RoundedCornerShape(10.dp))
                .padding(horizontal = 24.dp, vertical = 10.dp),
        )
        Text(
            statusText(state.status),
            style = MaterialTheme.typography.bodyLarge,
            color = if (state.status == Status.OFFLINE || state.status == Status.VPN_REQUIRED) MyTvColors.Error else MyTvColors.OnSurfaceMuted,
        )
        state.detail?.let {
            if (state.status == Status.OFFLINE || state.status == Status.CONNECTING) {
                Text("Raison : $it", style = MaterialTheme.typography.bodyMedium, color = MyTvColors.OnSurfaceMuted)
            }
        }
        state.event?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MyTvColors.Success) }
        Text(
            "L'aide reste active pendant que tu utilises MyTV (bandeau rouge en haut de l'écran). " +
                "Elle s'arrête toute seule quand la personne qui t'aide a terminé, en quittant MyTV, ou avec le bouton ci-dessous.",
            style = MaterialTheme.typography.bodyMedium,
            color = MyTvColors.OnSurfaceMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { remote.stop() }, modifier = Modifier.focusRequester(stopFocus)) { Text("Arrêter l'aide") }
            OutlinedButton(onClick = onClose) { Text("Retour") }
        }
    }
}

private fun statusText(status: Status) = when (status) {
    Status.OFF, Status.CONNECTING -> "Connexion au relais…"
    Status.WAITING -> "Prêt : en attente de la personne qui t'aide."
    Status.CONNECTED -> "Connecté : la personne qui t'aide voit ton écran."
    Status.OFFLINE -> "Le relais d'aide est injoignable, nouvel essai automatique…"
    Status.VPN_REQUIRED -> "Bloqué : active ton VPN (ou mets « Exiger un VPN » sur Non)."
}

/** Bandeau affiché par-dessus tous les écrans tant que l'aide à distance est active. */
@Composable
fun RemoteBanner(remote: RemoteControl, modifier: Modifier = Modifier) {
    val state by remote.state.collectAsStateWithLifecycle()
    if (state.status == Status.OFF) return
    val connected = state.status == Status.CONNECTED
    Text(
        if (connected) "● Aide à distance : on voit ton écran" else "● Aide à distance activée · code ${state.code}",
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = modifier
            .padding(top = 6.dp)
            .background(if (connected) Color(0xE6C62828) else Color(0xCC37474F), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 4.dp),
    )
}
