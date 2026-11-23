package dev.mytv.ui.portal

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.mytv.R
import dev.mytv.ui.common.summaryLines
import dev.mytv.ui.common.toMessage
import dev.mytv.ui.common.tryRequestFocus
import dev.mytv.ui.components.TvTextField
import dev.mytv.ui.portal.AddPortalUiState.Phase
import dev.mytv.ui.theme.MyTvColors

@Composable
fun AddPortalScreen(
    onPortalSaved: () -> Unit,
    /** Installation neuve : restaurer une sauvegarde au lieu de tout ressaisir. */
    onRestore: () -> Unit,
    /** Saisie depuis le téléphone (QR code) : bien plus rapide qu'au clavier de la télécommande. */
    onPhone: () -> Unit,
    viewModel: AddPortalViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.phase) {
        if (state.phase is Phase.Saved) onPortalSaved()
    }

    AddPortalContent(
        state = state,
        onNameChange = viewModel::onNameChange,
        onUrlChange = viewModel::onUrlChange,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onTest = viewModel::test,
        onSave = viewModel::save,
        onRestore = onRestore,
        onPhone = onPhone,
    )
}

@Composable
private fun AddPortalContent(
    state: AddPortalUiState,
    onNameChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onRestore: () -> Unit,
    onPhone: () -> Unit,
) {
    val phoneFocus = remember { FocusRequester() }
    val urlFocus = remember { FocusRequester() }
    val userFocus = remember { FocusRequester() }
    val passFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    val testFocus = remember { FocusRequester() }

    // Focus initial explicite : sans lui, rien n'est sélectionné à l'ouverture et la télécommande
    // semble morte. On part sur la saisie par téléphone, la plus rapide : un simple OK.
    LaunchedEffect(Unit) { phoneFocus.tryRequestFocus() }
    // Après un test réussi, on amène directement le focus sur « Enregistrer ».
    LaunchedEffect(state.phase) {
        if (state.phase is Phase.Tested) saveFocus.requestFocus()
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MyTvColors.Background)
            // Marges de sécurité TV (overscan) : ~5 % de l'écran.
            .padding(horizontal = 48.dp, vertical = 27.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        // --- Colonne gauche : titre, état, et accès à la restauration d'une sauvegarde
        Column(Modifier.weight(0.42f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.add_portal_title), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.add_portal_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MyTvColors.OnSurfaceMuted,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onPhone, modifier = Modifier.focusRequester(phoneFocus)) { Text(stringResource(R.string.action_phone_entry)) }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onRestore) { Text(stringResource(R.string.action_restore_backup)) }
            Spacer(Modifier.height(20.dp))
            StatusPanel(state.phase)
        }

        // --- Colonne droite : formulaire. focusGroup() garde la navigation D-pad dans le formulaire.
        Column(
            modifier = Modifier.weight(0.58f).fillMaxHeight().focusGroup(),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            TvTextField(
                label = stringResource(R.string.field_url),
                value = state.url,
                onValueChange = onUrlChange,
                placeholder = stringResource(R.string.field_url_hint),
                keyboardType = KeyboardType.Uri,
                focusRequester = urlFocus,
                onImeAction = { userFocus.requestFocus() },
            )
            TvTextField(
                label = stringResource(R.string.field_username),
                value = state.username,
                onValueChange = onUsernameChange,
                placeholder = stringResource(R.string.field_empty),
                focusRequester = userFocus,
                onImeAction = { passFocus.requestFocus() },
            )
            TvTextField(
                label = stringResource(R.string.field_password),
                value = state.password,
                onValueChange = onPasswordChange,
                placeholder = stringResource(R.string.field_empty),
                isPassword = true,
                imeAction = ImeAction.Done,
                focusRequester = passFocus,
            )
            TvTextField(
                label = stringResource(R.string.field_name),
                value = state.name,
                onValueChange = onNameChange,
                placeholder = stringResource(R.string.field_name_hint),
                imeAction = ImeAction.Done,
                // Sans cela, Bas choisit le bouton le plus proche géométriquement (« Enregistrer ») :
                // on impose un ordre prévisible, le premier bouton de la rangée.
                modifier = Modifier.focusProperties { down = testFocus },
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(
                    onClick = onTest,
                    enabled = state.canSubmit,
                    modifier = Modifier.focusRequester(testFocus),
                ) {
                    Text(stringResource(R.string.action_test))
                }
                Button(
                    onClick = onSave,
                    enabled = state.canSubmit,
                    modifier = Modifier.focusRequester(saveFocus),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

/** Bloc d'état : progression, succès avec infos du compte, ou erreur explicite. */
@Composable
private fun StatusPanel(phase: Phase) {
    val (color, lines) = when (phase) {
        Phase.Idle, Phase.Saved -> return
        Phase.Testing -> MyTvColors.OnSurfaceMuted to listOf(stringResource(R.string.status_testing))
        Phase.Saving -> MyTvColors.OnSurfaceMuted to listOf(stringResource(R.string.status_saving))
        is Phase.Tested -> MyTvColors.Success to listOf(stringResource(R.string.status_success)) +
            phase.check.account.summaryLines() +
            stringResource(if (phase.check.isEncrypted) R.string.connection_encrypted else R.string.connection_cleartext)
        is Phase.Failed -> MyTvColors.Error to listOf(phase.error.toMessage())
    }
    Column(
        Modifier
            .width(420.dp)
            .background(MyTvColors.Surface, RoundedCornerShape(12.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        lines.forEachIndexed { index, line ->
            Text(
                text = line,
                style = if (index == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                color = if (index == 0) color else Color.Unspecified,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
