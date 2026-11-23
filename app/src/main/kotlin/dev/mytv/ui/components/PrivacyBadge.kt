package dev.mytv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.mytv.ui.theme.MyTvColors

/**
 * Ce que le FAI peut voir, en un coup d'œil :
 * - VPN actif : rien d'exploitable (connexion chiffrée vers le serveur VPN) ;
 * - sans VPN mais portail HTTPS : l'adresse du serveur et le volume, pas le contenu ;
 * - sans VPN et portail HTTP : tout est lisible (URLs, identifiants, flux).
 * Les deux derniers cas n'existent que si l'utilisateur a choisi « Continuer sans VPN ».
 */
@Composable
fun PrivacyBadge(vpnActive: Boolean, vpnRequired: Boolean, portalEncrypted: Boolean) {
    val (text, color) = when {
        vpnActive && vpnRequired -> "🔒 VPN actif · verrouillé" to MyTvColors.Success
        vpnActive -> "🔒 VPN actif" to MyTvColors.Success
        // Sans VPN (choix de l'utilisateur) : on rappelle clairement ce que le FAI peut voir.
        portalEncrypted -> "⚠ Sans VPN · HTTPS" to Color(0xFFFFC94D)
        else -> "⚠ Sans VPN · non chiffré" to MyTvColors.Error
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
}
