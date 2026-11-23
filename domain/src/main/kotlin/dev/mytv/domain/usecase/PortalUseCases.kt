package dev.mytv.domain.usecase

import dev.mytv.domain.model.AccountInfo
import dev.mytv.domain.model.AccountStatus
import dev.mytv.domain.model.ConnectionCheck
import dev.mytv.domain.model.Portal
import dev.mytv.domain.model.PortalCredentials
import dev.mytv.domain.repository.PortalRepository
import dev.mytv.domain.result.AppError
import dev.mytv.domain.result.AppResult
import dev.mytv.domain.util.ServerUrl
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveActivePortalUseCase @Inject constructor(
    private val repository: PortalRepository,
) {
    operator fun invoke(): Flow<Portal?> = repository.observeActivePortal()
}

/**
 * Valide la saisie, normalise l'URL puis teste le portail.
 *
 * Confidentialité : si le portail est en HTTP mais annonce un port HTTPS, on essaie HTTPS et on
 * le retient s'il fonctionne. En HTTPS, le FAI ne voit plus ni les URLs, ni les identifiants, ni
 * le contenu des flux ; seulement l'adresse du serveur et le volume échangé.
 *
 * Un compte expiré/banni est remonté en erreur : il est inutile d'enregistrer un portail inutilisable.
 */
class TestPortalConnectionUseCase @Inject constructor(
    private val repository: PortalRepository,
) {
    suspend operator fun invoke(raw: PortalCredentials): AppResult<ConnectionCheck> {
        val credentials = raw.normalized() ?: return AppResult.Failure(AppError.InvalidUrl)
        if (credentials.username.isBlank() || credentials.password.isEmpty()) {
            return AppResult.Failure(AppError.InvalidCredentials)
        }
        val account = when (val result = repository.testConnection(credentials)) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> return result
        }
        account.checkUsable()?.let { return AppResult.Failure(it) }

        val httpsUrl = ServerUrl.httpsCandidate(credentials.serverUrl, account.httpsPort)
        if (httpsUrl != null) {
            val secure = credentials.copy(serverUrl = httpsUrl)
            val secureResult = repository.testConnection(secure)
            if (secureResult is AppResult.Success) return AppResult.Success(ConnectionCheck(secureResult.value, secure))
            // HTTPS annoncé mais inutilisable (certificat invalide, port fermé...) : on garde HTTP.
        }
        return AppResult.Success(ConnectionCheck(account, credentials))
    }
}

/** Teste le portail puis l'enregistre (avec l'URL effective, éventuellement HTTPS). */
class AddPortalUseCase @Inject constructor(
    private val repository: PortalRepository,
    private val testConnection: TestPortalConnectionUseCase,
) {
    suspend operator fun invoke(name: String, raw: PortalCredentials): AppResult<Portal> {
        val check = when (val test = testConnection(raw)) {
            is AppResult.Failure -> return test
            is AppResult.Success -> test.value
        }
        val credentials = check.credentials
        // Nom par défaut lisible : l'hôte du serveur.
        val displayName = name.trim().ifEmpty { credentials.serverUrl.substringAfter("://").substringBefore('/').substringBefore(':') }
        return repository.savePortal(displayName, credentials)
    }
}

/**
 * Pour un portail déjà enregistré en HTTP : tente la bascule en HTTPS (au démarrage), au cas
 * où le serveur l'aurait activé depuis. Silencieux en cas d'échec.
 */
class UpgradePortalToHttpsUseCase @Inject constructor(
    private val repository: PortalRepository,
) {
    suspend operator fun invoke(portal: Portal): Boolean =
        !portal.isEncrypted && repository.tryUpgradeToHttps(portal.id)
}

private fun PortalCredentials.normalized(): PortalCredentials? {
    val url = ServerUrl.normalize(serverUrl) ?: return null
    return copy(serverUrl = url, username = username.trim())
}

private fun AccountInfo.checkUsable(): AppError? = when (status) {
    AccountStatus.ACTIVE, AccountStatus.UNKNOWN -> null
    else -> AppError.AccountUnavailable(status.name)
}
