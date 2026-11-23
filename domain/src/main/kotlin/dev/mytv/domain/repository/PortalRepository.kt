package dev.mytv.domain.repository

import dev.mytv.domain.model.AccountInfo
import dev.mytv.domain.model.Portal
import dev.mytv.domain.model.PortalCredentials
import dev.mytv.domain.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Contrat implémenté par la couche data. L'UI ne connaît que cette interface (via les use cases). */
interface PortalRepository {
    /** Portail actif, ou null si aucun n'a encore été configuré. */
    fun observeActivePortal(): Flow<Portal?>

    /** Interroge le portail sans rien enregistrer. */
    suspend fun testConnection(credentials: PortalCredentials): AppResult<AccountInfo>

    /** Enregistre le portail (mot de passe chiffré) et le rend actif. */
    suspend fun savePortal(name: String, credentials: PortalCredentials): AppResult<Portal>

    /**
     * Portail enregistré en HTTP : s'il annonce un port HTTPS fonctionnel, met à jour son URL
     * (le cache de chaînes est conservé). Renvoie true si la bascule a eu lieu.
     */
    suspend fun tryUpgradeToHttps(portalId: Long): Boolean
}
