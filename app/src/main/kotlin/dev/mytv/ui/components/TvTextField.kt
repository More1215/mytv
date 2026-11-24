package dev.mytv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.mytv.remote.RemoteTextInput
import dev.mytv.ui.theme.MyTvColors

/**
 * Champ texte adapté à la télécommande.
 *
 * Problème : un BasicTextField classique ouvre le clavier dès qu'il reçoit le focus et capture
 * les flèches gauche/droite (curseur), ce qui piège la navigation D-pad.
 *
 * Solution : UNE seule cible de focus (le champ lui-même, jamais retiré de l'arbre), avec deux modes :
 * - **navigation** : lecture seule, clavier fermé ; toutes les flèches déplacent le focus ;
 * - **édition** (après OK) : clavier ouvert. On en sort par l'action du clavier, par Retour,
 *   ou par Haut/Bas (qui déplacent aussi le focus).
 *
 * Le focus ne change jamais de nœud en entrant/sortant de l'édition : c'est ce qui évite les
 * écrans « sans focus » (D-pad inopérant) observés quand on bascule entre deux composants.
 */
@Composable
fun TvTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    /** Appelée après l'action du clavier (ex. passer au champ suivant). */
    onImeAction: () -> Unit = {},
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    var editing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Aide à distance : le champ qui a le focus peut être rempli depuis la page web.
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    DisposableEffect(focused) {
        if (!focused) return@DisposableEffect onDispose { }
        val sink: (String) -> Unit = { currentOnValueChange(it) }
        RemoteTextInput.sink = sink
        onDispose { if (RemoteTextInput.sink === sink) RemoteTextInput.sink = null }
    }

    fun startEditing() {
        editing = true
        keyboard?.show()
    }

    fun stopEditing() {
        editing = false
        keyboard?.hide()
    }

    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value = value,
        onValueChange = { if (editing) onValueChange(it) },
        readOnly = !editing,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MyTvColors.OnBackground),
        cursorBrush = if (editing) SolidColor(MyTvColors.Accent) else SolidColor(Color.Transparent),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isPassword) KeyboardType.Password else keyboardType,
            imeAction = imeAction,
            autoCorrectEnabled = false,
            // Le clavier ne s'ouvre que sur OK, pas au simple passage du focus.
            showKeyboardOnFocus = false,
        ),
        keyboardActions = KeyboardActions(onAny = {
            stopEditing()
            onImeAction()
        }),
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused && editing) stopEditing()
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (!editing) {
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { startEditing(); true }
                        // En navigation, gauche/droite sortent du champ au lieu de bouger le curseur.
                        Key.DirectionLeft -> focusManager.moveFocus(FocusDirection.Left)
                        Key.DirectionRight -> focusManager.moveFocus(FocusDirection.Right)
                        Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                        else -> false
                    }
                } else {
                    when (event.key) {
                        Key.Back, Key.Escape -> { stopEditing(); true }
                        Key.DirectionUp -> { stopEditing(); focusManager.moveFocus(FocusDirection.Up); true }
                        Key.DirectionDown -> { stopEditing(); focusManager.moveFocus(FocusDirection.Down); true }
                        else -> false
                    }
                }
            },
        decorationBox = { innerTextField ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(if (focused) MyTvColors.SurfaceFocused else MyTvColors.Surface, shape)
                    .border(2.dp, if (focused) MyTvColors.Accent else Color.Transparent, shape)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MyTvColors.OnSurfaceMuted)
                if (value.isEmpty() && !editing) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MyTvColors.OnSurfaceMuted, maxLines = 1)
                } else {
                    innerTextField()
                }
            }
        },
    )
}
