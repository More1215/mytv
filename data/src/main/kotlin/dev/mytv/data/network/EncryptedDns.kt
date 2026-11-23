package dev.mytv.data.network

import dev.mytv.data.BuildConfig
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Résolution DNS chiffrée (DNS over HTTPS) pour TOUT le trafic de l'appli : API du portail,
 * guide XMLTV, flux vidéo (ExoPlayer) et logos (Coil) passent par le même OkHttpClient.
 *
 * Pourquoi : avec le DNS classique du FAI, celui-ci voit en clair chaque nom de serveur contacté,
 * et c'est aussi au niveau DNS que s'appliquent la plupart des blocages. Ici, les requêtes DNS
 * partent chiffrées vers un résolveur public gratuit ; le FAI ne voit qu'une connexion HTTPS vers
 * ce résolveur.
 *
 * Ordre : Cloudflare, puis Quad9 si Cloudflare est injoignable. Les adresses IP des résolveurs
 * sont figées (« bootstrap ») pour ne JAMAIS passer par le DNS du FAI afin de les trouver.
 *
 * Limite assumée : si aucun résolveur chiffré ne répond (réseau très filtré), on retombe sur le
 * DNS du système plutôt que de rendre l'appli inutilisable. [lastResolution] permet de l'afficher.
 */
class EncryptedDns internal constructor(
    /** Résolveurs chiffrés, dans l'ordre d'essai. */
    private val resolvers: List<Dns>,
    /** Kill switch : si le VPN est requis et absent, aucun repli sur le DNS en clair. */
    private val isBlocked: () -> Boolean,
) : Dns {

    constructor(bootstrapClient: OkHttpClient, isBlocked: () -> Boolean = { false }) : this(
        listOf(
            doh(bootstrapClient, "https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1"),
            doh(bootstrapClient, "https://dns.quad9.net/dns-query", "9.9.9.9", "149.112.112.112"),
        ),
        isBlocked,
    )

    enum class Resolution { ENCRYPTED, SYSTEM_FALLBACK, NONE_YET }

    @Volatile
    var lastResolution: Resolution = Resolution.NONE_YET
        private set

    override fun lookup(hostname: String): List<InetAddress> {
        for (resolver in resolvers) {
            try {
                val result = resolver.lookup(hostname)
                if (result.isNotEmpty()) {
                    lastResolution = Resolution.ENCRYPTED
                    debugLog("résolution chiffrée (DoH) OK")
                    return result
                }
            } catch (_: UnknownHostException) {
                // Résolveur injoignable ou nom inconnu : on essaie le suivant.
            }
        }
        // Le VPN a pu tomber pendant la résolution : jamais de DNS en clair hors tunnel.
        if (isBlocked()) throw VpnRequiredException()
        // Dernier recours (ex. nom de machine sur le réseau local, DoH bloqué). Avec un VPN actif,
        // le DNS système passe lui aussi dans le tunnel.
        lastResolution = Resolution.SYSTEM_FALLBACK
        debugLog("DoH indisponible : repli sur le DNS système")
        return Dns.SYSTEM.lookup(hostname)
    }

    /** En debug uniquement, et sans le nom d'hôte (il identifierait le portail). */
    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("MyTV-DNS", message)
    }

    private companion object {
        fun doh(client: OkHttpClient, url: String, vararg bootstrapIps: String): Dns =
            DnsOverHttps.Builder()
                .client(client)
                .url(url.toHttpUrl())
                .bootstrapDnsHosts(bootstrapIps.map { InetAddress.getByName(it) })
                // Résout aussi les IPv6 : sinon un serveur uniquement IPv6 serait injoignable.
                .includeIPv6(true)
                .build()
    }
}
