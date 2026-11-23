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
 * En release, les deux derniers cas n'existent pas : le kill switch bloque l'appli.
 */
@Composable
fun PrivacyBadge(vpnActive: Boolean, vpnRequired: Boolean, portalEncrypted: Boolean) {
    val (text, color) = when {
        vpnActive && vpnRequired -> "🔒 VPN actif · verrouillé" to MyTvColors.Success
        vpnActive -> "🔒 VPN actif" to MyTvColors.Success
        // Kill switch levé (build de debug uniquement) : on le signale très clairement.
        !vpnRequired -> "⚠ DEBUG : kill switch désactivé" to MyTvColors.Error
        portalEncrypted -> "⚠ Sans VPN · HTTPS" to Color(0xFFFFC94D)
        else -> "⚠ Sans VPN · HTTP non chiffré" to MyTvColors.Error
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
}
