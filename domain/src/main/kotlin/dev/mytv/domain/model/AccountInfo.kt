package dev.mytv.domain.model

/** État du compte renvoyé par le portail lors de l'authentification. */
enum class AccountStatus { ACTIVE, EXPIRED, BANNED, DISABLED, UNKNOWN }

data class AccountInfo(
    val username: String,
    val status: AccountStatus,
    /** Date d'expiration en secondes epoch, null si illimité ou non communiqué. */
    val expiresAtEpochSec: Long?,
    val isTrial: Boolean,
    val activeConnections: Int?,
    val maxConnections: Int?,
    /** Formats de sortie proposés par le serveur (ex. "m3u8", "ts"). */
    val allowedOutputFormats: List<String>,
    val serverTimezone: String?,
    /** Port HTTPS annoncé par le serveur (server_info.https_port), null si aucun. */
    val httpsPort: Int? = null,
)

/**
 * Résultat d'un test de connexion : les infos du compte, et les identifiants EFFECTIFS à
 * enregistrer (l'URL a pu être basculée en HTTPS si le portail le permet).
 */
data class ConnectionCheck(
    val account: AccountInfo,
    val credentials: PortalCredentials,
) {
    val isEncrypted: Boolean get() = credentials.serverUrl.startsWith("https://", ignoreCase = true)
}
