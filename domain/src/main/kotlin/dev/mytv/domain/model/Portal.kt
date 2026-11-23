package dev.mytv.domain.model

/** Type de source. Seul Xtream existe aujourd'hui ; M3U viendra s'ajouter ici. */
enum class PortalType { XTREAM }

/**
 * Un portail enregistré. Ne contient volontairement PAS le mot de passe :
 * celui-ci reste chiffré dans la couche data et n'en sort que pour les appels réseau.
 */
data class Portal(
    val id: Long,
    val name: String,
    val type: PortalType,
    val serverUrl: String,
    val username: String,
) {
    /** true si l'API et les flux passent en HTTPS (contenu illisible par le FAI). */
    val isEncrypted: Boolean get() = serverUrl.startsWith("https://", ignoreCase = true)
}

/** Identifiants saisis par l'utilisateur, utilisés pour tester puis enregistrer un portail. */
data class PortalCredentials(
    val serverUrl: String,
    val username: String,
    val password: String,
) {
    /** On masque le mot de passe pour qu'il ne finisse jamais dans un log par accident. */
    override fun toString(): String = "PortalCredentials(serverUrl=$serverUrl, username=$username, password=***)"
}
