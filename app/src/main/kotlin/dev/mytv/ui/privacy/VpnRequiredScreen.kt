package dev.mytv.ui.privacy

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.mytv.R
import dev.mytv.ui.common.tryRequestFocus
import dev.mytv.ui.theme.MyTvColors

/**
 * Écran bloquant du kill switch, affiché PAR-DESSUS toute l'appli tant qu'aucun VPN n'est actif.
 * Il capte le focus (rien en dessous n'est atteignable au D-pad) et disparaît tout seul dès que
 * le VPN se connecte : l'écran précédent est alors retrouvé tel quel.
 */
@Composable
fun VpnRequiredScreen() {
    val activity = LocalActivity.current
    val quitFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { quitFocus.tryRequestFocus() }
    // Retour : on quitte l'appli plutôt que de naviguer dans des écrans inutilisables.
    BackHandler { activity?.finish() }

    Box(
        Modifier
            .fillMaxSize()
            .background(MyTvColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(720.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("🔒", style = MaterialTheme.typography.displaySmall)
            Text(stringResource(R.string.vpn_required_title), style = MaterialTheme.typography.displaySmall)
            Text(
                stringResource(R.string.vpn_required_body),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.vpn_required_steps),
                style = MaterialTheme.typography.bodyLarge,
                color = MyTvColors.OnSurfaceMuted,
                textAlign = TextAlign.Center,
            )
            Text(stringResource(R.string.vpn_required_waiting), color = MyTvColors.Accent, style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { activity?.finish() }, modifier = Modifier.focusRequester(quitFocus)) {
                Text(stringResource(R.string.vpn_required_quit))
            }
        }
    }
}
