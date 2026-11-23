package dev.mytv.domain.repository

import kotlinx.coroutines.flow.StateFlow

/**
 * État de protection du réseau.
 *
 * @property vpnActive le trafic de l'appli passe par un VPN (le FAI ne voit que le tunnel chiffré).
 * @property vpnRequired le « kill switch » est actif : sans VPN, l'appli ne fait AUCUNE connexion.
 */
data class NetworkProtection(val vpnActive: Boolean, val vpnRequired: Boolean) {
    /** true = tout trafic réseau est bloqué tant qu'un VPN n'est pas actif. */
    val blocked: Boolean get() = vpnRequired && !vpnActive
}

/** Implémenté par la couche data (surveillance du réseau Android + verrou OkHttp). */
interface NetworkGuard {
    val protection: StateFlow<NetworkProtection>
}
