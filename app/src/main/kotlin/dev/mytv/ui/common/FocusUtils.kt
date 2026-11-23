package dev.mytv.ui.common

import androidx.compose.ui.focus.FocusRequester

/**
 * requestFocus() lève une exception si le composant ciblé n'est pas (encore) affiché — fréquent
 * avec les listes paresseuses. Sur TV, mieux vaut ignorer la demande que planter l'appli.
 */
fun FocusRequester.tryRequestFocus(): Boolean = try {
    requestFocus()
} catch (_: IllegalStateException) {
    false
}
