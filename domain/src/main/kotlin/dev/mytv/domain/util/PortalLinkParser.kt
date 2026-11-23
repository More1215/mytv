package dev.mytv.domain.util

import dev.mytv.domain.model.PortalCredentials
import java.net.URI
import java.net.URLDecoder
import java.text.Normalizer

/**
 * Extrait les identifiants d'un portail de ce que l'utilisateur colle, pour lui éviter de tout
 * retaper. Formats reconnus :
 *
 * 1. Lien fournisseur : `http://serveur:8080/get.php?username=U&password=P&type=m3u_plus`
 *    (aussi player_api.php, xmltv.php...) ;
 * 2. Lien de flux : `http://serveur:8080/live/U/P/123.ts` ou `http://serveur:8080/U/P/123` ;
 * 3. Texte du fournisseur, une info par ligne : « Domain: … », « Username: … », « Password: … »
 *    (libellés anglais ou français, valeur sur la même ligne ou la suivante, « Port: » séparé).
 *
 * Renvoie null si rien d'exploitable. Aucune vérification réseau ici : le test de connexion
 * habituel (et la normalisation de l'URL) s'applique ensuite.
 */
object PortalLinkParser {

    private val urlRegex = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    private val streamTypes = setOf("live", "movie", "series", "timeshift")

    fun parse(text: String): PortalCredentials? = fromLink(text) ?: fromLabels(text)

    // ------------------------------------------------------------------ Lien

    private fun fromLink(text: String): PortalCredentials? {
        val link = urlRegex.find(text)?.value?.trimEnd('.', ',', ';', ')') ?: return null
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val base = "${uri.scheme.lowercase()}://$host" + if (uri.port != -1) ":${uri.port}" else ""

        val params = uri.rawQuery.orEmpty().split('&').filter { '=' in it }.associate {
            decode(it.substringBefore('=')).lowercase() to decode(it.substringAfter('='))
        }
        val user = params["username"] ?: params["user"]
        val pass = params["password"] ?: params["pass"]
        if (!user.isNullOrBlank() && !pass.isNullOrEmpty()) return PortalCredentials(base, user.trim(), pass)

        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }.map(::decode)
        return when {
            // /live/U/P/123.ts
            segments.size >= 4 && segments[0].lowercase() in streamTypes ->
                PortalCredentials(base, segments[1], segments[2])
            // /U/P/123 (forme courte des listes M3U)
            segments.size >= 3 && segments.last().substringBefore('.').all(Char::isDigit) ->
                PortalCredentials(base, segments[segments.size - 3], segments[segments.size - 2])
            else -> null
        }
    }

    // ------------------------------------------------------------------ Texte « libellé : valeur »

    private enum class Field { URL, PORT, USER, PASS }

    private fun fromLabels(text: String): PortalCredentials? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val found = HashMap<Field, String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val sep = line.indexOfAny(charArrayOf(':', '=', '\t'))
            val label = if (sep > 0) line.substring(0, sep) else line
            val field = fieldFor(label)
            if (field != null && field !in found) {
                var value = if (sep > 0) line.substring(sep + 1).trim() else ""
                // Valeur sur la ligne suivante (copie d'un tableau « Domain / fairy... »).
                if (value.isEmpty() && i + 1 < lines.size && fieldFor(lines[i + 1].substringBefore(':')) == null) {
                    value = lines[++i]
                }
                if (value.isNotEmpty()) found[field] = value
            }
            i++
        }
        var url = found[Field.URL] ?: return null
        val user = found[Field.USER] ?: return null
        val pass = found[Field.PASS] ?: return null
        val port = found[Field.PORT]?.filter(Char::isDigit)
        if (!port.isNullOrEmpty() && !url.substringAfter("://").substringBefore('/').contains(':')) {
            val scheme = if (url.contains("://")) url.substringBefore("://") + "://" else ""
            val rest = url.substringAfter("://")
            url = scheme + rest.substringBefore('/') + ":" + port + rest.removePrefix(rest.substringBefore('/'))
        }
        return PortalCredentials(url.trim(), user.trim(), pass.trim())
    }

    /** « Nom d'utilisateur », « USERNAME », « Mot de passe »... -> champ, ou null si inconnu. */
    private fun fieldFor(label: String): Field? {
        val key = Normalizer.normalize(label, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .filter(Char::isLetter)
        if (key.isEmpty() || key.length > 20) return null
        return when {
            // Liste exacte d'abord : « URL du serveur » donne « urlduserveur », qui contient « user ».
            key in urlLabels -> Field.URL
            key == "port" -> Field.PORT
            "pass" in key || "motdepasse" in key || key == "mdp" || key == "pwd" -> Field.PASS
            "user" in key || "utilisateur" in key || "login" in key || "identifiant" in key -> Field.USER
            else -> null
        }
    }

    private val urlLabels = setOf(
        "domain", "domaine", "host", "hote", "hostname", "server", "serveur", "url", "serverurl",
        "urlduserveur", "portal", "portail", "dns", "adresse", "address", "lien", "link",
    )

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
