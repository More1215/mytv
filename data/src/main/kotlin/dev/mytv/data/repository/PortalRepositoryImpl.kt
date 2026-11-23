package dev.mytv.data.repository

import dev.mytv.data.error.runCatchingApp
import dev.mytv.data.local.db.PortalDao
import dev.mytv.data.local.db.PortalEntity
import dev.mytv.data.local.prefs.AppPreferences
import dev.mytv.data.security.CredentialCipher
import dev.mytv.data.source.IptvSourceFactory
import dev.mytv.domain.model.AccountInfo
import dev.mytv.domain.model.Portal
import dev.mytv.domain.model.PortalCredentials
import dev.mytv.domain.model.PortalType
import dev.mytv.domain.repository.PortalRepository
import dev.mytv.domain.result.AppResult
import dev.mytv.domain.util.ServerUrl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class PortalRepositoryImpl @Inject constructor(
    private val portalDao: PortalDao,
    private val preferences: AppPreferences,
    private val cipher: CredentialCipher,
    private val sourceFactory: IptvSourceFactory,
    private val sources: SourceProvider,
    @param:Named(IO_DISPATCHER) private val io: CoroutineDispatcher,
) : PortalRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeActivePortal(): Flow<Portal?> =
        preferences.activePortalId
            .distinctUntilChanged()
            .flatMapLatest { id -> if (id == null) flowOf(null) else portalDao.observeById(id) }
            .map { it?.toDomain() }

    override fun observePortals(): Flow<List<Portal>> =
        portalDao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun setActivePortal(portalId: Long) = withContext(io) {
        preferences.setActivePortalId(portalId)
    }

    override suspend fun deletePortal(portalId: Long) = withContext(io) {
        val wasActive = preferences.activePortalId.first() == portalId
        portalDao.deleteEverything(portalId)
        preferences.clearPortal(portalId)
        sources.invalidate(portalId)
        if (wasActive) {
            // Un autre portail prend le relais ; sans portail, l'appli revient à « Ajouter un portail ».
            val next = portalDao.getAll().firstOrNull()
            if (next != null) preferences.setActivePortalId(next.id) else preferences.clearActivePortalId()
        }
    }

    override suspend fun testConnection(credentials: PortalCredentials): AppResult<AccountInfo> =
        withContext(io) {
            runCatchingApp { sourceFactory.create(PortalType.XTREAM, credentials).authenticate() }
        }

    override suspend fun savePortal(name: String, credentials: PortalCredentials): AppResult<Portal> =
        withContext(io) {
            runCatchingApp {
                // Même serveur + même login = mise à jour (nouveau mot de passe), pas de doublon.
                val existing = portalDao.findByLogin(credentials.serverUrl, credentials.username)
                val entity = PortalEntity(
                    id = existing?.id ?: 0,
                    name = name,
                    type = PortalType.XTREAM.name,
                    serverUrl = credentials.serverUrl,
                    username = credentials.username,
                    encryptedPassword = cipher.encrypt(credentials.password),
                    createdAtEpochMs = existing?.createdAtEpochMs ?: System.currentTimeMillis(),
                )
                // @Upsert renvoie -1 lors d'une mise à jour : on garde alors l'id existant.
                val id = portalDao.upsert(entity).takeIf { it > 0 } ?: entity.id
                // Le mot de passe a pu changer : la source en cache (identifiants déchiffrés) est périmée.
                sources.invalidate(id)
                preferences.setActivePortalId(id)
                entity.copy(id = id).toDomain()
            }
        }

    override suspend fun tryUpgradeToHttps(portalId: Long): Boolean = withContext(io) {
        val entity = portalDao.getById(portalId) ?: return@withContext false
        val credentials = PortalCredentials(entity.serverUrl, entity.username, cipher.decrypt(entity.encryptedPassword))
        val type = PortalType.valueOf(entity.type)
        val account = runCatchingApp { sourceFactory.create(type, credentials).authenticate() }
        if (account !is AppResult.Success) return@withContext false
        val httpsUrl = ServerUrl.httpsCandidate(entity.serverUrl, account.value.httpsPort) ?: return@withContext false
        val secure = runCatchingApp { sourceFactory.create(type, credentials.copy(serverUrl = httpsUrl)).authenticate() }
        if (secure !is AppResult.Success) return@withContext false
        portalDao.updateServerUrl(portalId, httpsUrl)
        // Les URLs de flux sont construites par la source en cache : elle doit être recréée.
        sources.invalidate(portalId)
        true
    }

    companion object {
        const val IO_DISPATCHER = "io"
    }
}

private fun PortalEntity.toDomain() = Portal(
    id = id,
    name = name,
    type = PortalType.valueOf(type),
    serverUrl = serverUrl,
    username = username,
)
