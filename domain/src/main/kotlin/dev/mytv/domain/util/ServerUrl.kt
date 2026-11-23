package dev.mytv.domain.util

/**
 * Normalise l'URL saisie à la télécommande, où les fautes de frappe sont fréquentes :
 * - ajoute "http://" si le schéma manque (la majorité des portails Xtream sont en HTTP) ;
 * - retire les espaces, le "/" final et un éventuel "player_api.php", "get.php"...
 *   copié depuis une URL M3U fournie par le revendeur.
 *
 * Renvoie null si l'URL est inexploitable.
 */
object ServerUrl {
    private val trailingEndpoints = listOf("player_api.php", "get.php", "xmltv.php", "panel_api.php")

    fun normalize(raw: String): String? {
        var url = raw.trim().replace(" ", "")
        if (url.isEmpty()) return null

        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            url = "http://$url"
        }
        // On coupe la query string éventuelle (?username=...&password=...).
        url = url.substringBefore('?').trimEnd('/')
        trailingEndpoints.firstOrNull { url.endsWith("/$it", ignoreCase = true) }?.let {
            url = url.dropLast(it.length + 1)
        }

        val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
        if (host.isEmpty() || !host.contains('.') && host != "localhost") return null
        return url
    }

    /**
     * Variante HTTPS d'une URL HTTP normalisée, sur le port HTTPS annoncé par le serveur.
     * Ex. ("http://exemple.com:8080/panel", 8443) -> "https://exemple.com:8443/panel".
     * null si l'URL est déjà en HTTPS ou si aucun port HTTPS valide n'est connu.
     */
    fun httpsCandidate(httpUrl: String, httpsPort: Int?): String? {
        if (!httpUrl.startsWith("http://", ignoreCase = true)) return null
        if (httpsPort == null || httpsPort !in 1..65535) return null
        val rest = httpUrl.substring("http://".length)
        val hostPort = rest.substringBefore('/')
        val path = rest.substring(hostPort.length)
        val host = if (hostPort.startsWith("[")) hostPort.substringBefore("]") + "]" else hostPort.substringBefore(':')
        val portPart = if (httpsPort == 443) "" else ":$httpsPort"
        return "https://$host$portPart$path"
    }
}
