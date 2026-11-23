package dev.mytv.data.repository

import dev.mytv.data.backup.BackupCrypto
import dev.mytv.data.backup.BackupFavorite
import dev.mytv.data.backup.BackupPayload
import dev.mytv.data.backup.BackupPortal
import dev.mytv.data.backup.BackupResume
import dev.mytv.data.backup.PortalRef
import dev.mytv.data.local.db.BackupDao
import dev.mytv.data.local.db.LiveFavoriteEntity
import dev.mytv.data.local.db.PortalDao
import dev.mytv.data.local.db.PortalEntity
import dev.mytv.data.local.db.ResumeEntity
import dev.mytv.data.local.prefs.AppPreferences
import dev.mytv.data.security.CredentialCipher
import dev.mytv.domain.repository.BackupRepository
import dev.mytv.domain.repository.RestoreSummary
import dev.mytv.domain.result.AppError
import dev.mytv.domain.result.AppResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class BackupRepositoryImpl @Inject constructor(
    private val portalDao: PortalDao,
    private val backupDao: BackupDao,
    private val preferences: AppPreferences,
    private val cipher: CredentialCipher,
    private val sources: SourceProvider,
    @param:Named(PortalRepositoryImpl.IO_DISPATCHER) private val io: CoroutineDispatcher,
) : BackupRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun export(passphrase: String): AppResult<ByteArray> = withContext(io) {
        try {
            val portals = portalDao.getAll()
            val refs = portals.associate { it.id to PortalRef(it.serverUrl, it.username) }
            val activeId = preferences.activePortalId.first()
            val payload = BackupPayload(
                createdAtMs = System.currentTimeMillis(),
                activePortal = activeId?.let(refs::get),
                portals = portals.map {
                    BackupPortal(it.name, it.type, it.serverUrl, it.username, cipher.decrypt(it.encryptedPassword), it.createdAtEpochMs)
                },
                favorites = backupDao.allFavorites().mapNotNull { f ->
                    refs[f.portalId]?.let { BackupFavorite(it, f.streamId, f.addedAtMs) }
                },
                resumePoints = backupDao.allResumePoints().mapNotNull { r ->
                    refs[r.portalId]?.let {
                        BackupResume(it, r.contentKey, r.positionMs, r.durationMs, r.title, r.subtitle, r.imageUrl, r.seriesId, r.updatedAtMs)
                    }
                },
                playerSettings = preferences.playerSettingsRaw.first(),
            )
            val plain = json.encodeToString(BackupPayload.serializer(), payload).toByteArray(Charsets.UTF_8)
            AppResult.Success(BackupCrypto.encrypt(plain, passphrase))
        } catch (e: Exception) {
            AppResult.Failure(AppError.Unknown(e.message))
        }
    }

    /**
     * Restauration « par fusion » : les portails sont retrouvés par URL + utilisateur (mot de passe
     * mis à jour s'ils existent déjà), et les favoris / reprises s'ajoutent à l'existant.
     * Rien n'est effacé : restaurer deux fois de suite ne crée pas de doublon.
     */
    override suspend fun restore(content: ByteArray, passphrase: String): AppResult<RestoreSummary> = withContext(io) {
        val payload = try {
            json.decodeFromString(BackupPayload.serializer(), BackupCrypto.decrypt(content, passphrase).toString(Charsets.UTF_8))
        } catch (e: Exception) {
            return@withContext AppResult.Failure(AppError.BackupUnreadable)
        }
        try {
            val idByRef = HashMap<PortalRef, Long>()
            payload.portals.forEach { p ->
                val existing = portalDao.findByLogin(p.serverUrl, p.username)
                val entity = PortalEntity(
                    id = existing?.id ?: 0,
                    name = p.name,
                    type = p.type,
                    serverUrl = p.serverUrl,
                    username = p.username,
                    // Rechiffré avec la clé Keystore de CET appareil.
                    encryptedPassword = cipher.encrypt(p.password),
                    createdAtEpochMs = existing?.createdAtEpochMs ?: p.createdAtMs,
                )
                val id = portalDao.upsert(entity).takeIf { it > 0 } ?: entity.id
                idByRef[PortalRef(p.serverUrl, p.username)] = id
                sources.invalidate(id)
            }
            val favorites = payload.favorites.mapNotNull { f -> idByRef[f.portal]?.let { LiveFavoriteEntity(it, f.streamId, f.addedAtMs) } }
            val resumes = payload.resumePoints.mapNotNull { r ->
                idByRef[r.portal]?.let {
                    ResumeEntity(it, r.key, r.positionMs, r.durationMs, r.title, r.subtitle, r.imageUrl, r.seriesId, r.updatedAtMs)
                }
            }
            backupDao.insertFavorites(favorites)
            backupDao.insertResumePoints(resumes)
            if (payload.playerSettings.isNotEmpty()) preferences.setPlayerSettingsRaw(payload.playerSettings)
            (payload.activePortal?.let(idByRef::get) ?: idByRef.values.firstOrNull())?.let { preferences.setActivePortalId(it) }
            AppResult.Success(RestoreSummary(idByRef.size, favorites.size, resumes.size))
        } catch (e: Exception) {
            AppResult.Failure(AppError.Unknown(e.message))
        }
    }
}
