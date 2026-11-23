package dev.mytv.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.mytv.data.BuildConfig
import dev.mytv.domain.repository.NetworkGuard
import dev.mytv.domain.repository.NetworkProtection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Surveille en temps réel si le trafic DE CETTE APPLI passe par un VPN, et porte le « kill switch ».
 *
 * Pourquoi un VPN est indispensable : sans tunnel, le FAI voit l'adresse IP de chaque serveur
 * contacté (le portail IPTV), quoi que fasse l'appli (DNS chiffré, HTTPS...). Avec un VPN, il ne
 * voit qu'une connexion chiffrée vers le serveur VPN.
 *
 * Fiabilité de la détection : ConnectivityManager renvoie le réseau par défaut DE L'APPLI. Si un
 * VPN tourne mais exclut cette appli (split tunneling), il n'est pas vu comme actif : c'est voulu,
 * puisque dans ce cas notre trafic ne passe justement pas dans le tunnel.
 */
@Singleton
class VpnMonitor @Inject constructor(
    @param:ApplicationContext context: Context,
) : NetworkGuard {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /**
     * Choix « exiger un VPN » : SharedPreferences (lecture synchrone, quelques octets) car il doit
     * être connu dès la toute première requête. Par défaut : VPN exigé en release.
     */
    private val prefs = context.getSharedPreferences("network_guard", Context.MODE_PRIVATE)

    private val _protection = MutableStateFlow(
        NetworkProtection(
            vpnActive = currentlyOnVpn(),
            vpnRequired = prefs.getBoolean(KEY_REQUIRE_VPN, BuildConfig.REQUIRE_VPN),
        ),
    )
    override val protection: StateFlow<NetworkProtection> = _protection.asStateFlow()

    init {
        connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                setVpn(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            }

            override fun onLost(network: Network) {
                setVpn(currentlyOnVpn())
            }
        })
    }

    override fun setVpnRequired(required: Boolean) {
        prefs.edit().putBoolean(KEY_REQUIRE_VPN, required).apply()
        _protection.update { it.copy(vpnRequired = required) }
    }

    /** Lu à chaque requête par le kill switch : doit être instantané et sans verrou. */
    fun isBlocked(): Boolean = _protection.value.blocked

    private fun setVpn(active: Boolean) = _protection.update { it.copy(vpnActive = active) }

    private companion object {
        const val KEY_REQUIRE_VPN = "require_vpn"
    }

    private fun currentlyOnVpn(): Boolean {
        val cm = connectivity ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
}

/** Levée quand le kill switch bloque une connexion. IOException : OkHttp, Retrofit et ExoPlayer la gèrent comme une coupure réseau. */
class VpnRequiredException : IOException("Connexion bloquée : aucun VPN actif")

/**
 * Kill switch : intercepteur d'APPLICATION, donc exécuté AVANT toute résolution DNS et toute
 * ouverture de socket. Sans VPN, aucun octet ne quitte l'appareil : ni requête DNS, ni connexion
 * au portail, ni flux vidéo, ni logo.
 *
 * La vérification est refaite à CHAQUE requête : une coupure du VPN en cours de lecture bloque
 * immédiatement les requêtes suivantes (et le lecteur est en plus arrêté côté UI).
 */
class KillSwitchInterceptor(private val isBlocked: () -> Boolean) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (isBlocked()) throw VpnRequiredException()
        return chain.proceed(chain.request())
    }
}
