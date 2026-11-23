package dev.mytv.domain.result

/**
 * Résultat typé utilisé à la frontière domain : l'UI reçoit une erreur métier
 * ([AppError]) et jamais une exception réseau brute.
 */
sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

/** Erreurs métier compréhensibles par l'UI, indépendantes de Retrofit/OkHttp. */
sealed interface AppError {
    /** URL vide ou impossible à interpréter. */
    data object InvalidUrl : AppError
    /** Nom d'utilisateur ou mot de passe refusé par le portail. */
    data object InvalidCredentials : AppError
    /** Compte reconnu mais inutilisable (expiré, banni, désactivé). */
    data class AccountUnavailable(val status: String) : AppError
    /** Hôte introuvable, connexion refusée, pas de réseau. */
    data object Unreachable : AppError
    /** Kill switch : connexion refusée par l'appli car aucun VPN n'est actif. */
    data object VpnRequired : AppError
    /** Le serveur n'a pas répondu à temps. */
    data object Timeout : AppError
    /** Le serveur répond, mais pas comme un portail Xtream (HTML, JSON inattendu...). */
    data object InvalidResponse : AppError
    /** Erreur HTTP côté serveur (5xx, 404...). */
    data class Server(val httpCode: Int) : AppError
    /** Sauvegarde illisible : mauvaise phrase secrète, ou fichier qui n'est pas une sauvegarde MyTV. */
    data object BackupUnreadable : AppError
    /** Filet de sécurité : cas non prévu. */
    data class Unknown(val message: String?) : AppError
}
