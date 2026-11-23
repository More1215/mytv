package dev.mytv.data.repository

import dev.mytv.data.local.db.PortalDao
import dev.mytv.data.security.CredentialCipher
import dev.mytv.data.source.IptvSource
import dev.mytv.data.source.IptvSourceFactory
import dev.mytv.domain.model.PortalCredentials
import dev.mytv.domain.model.PortalType
import dev.mytv.domain.repository.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fournit l'[IptvSource] d'un portail enregistré, en déchiffrant le mot de passe une seule fois
 * puis en gardant la source en mémoire : le zapping construit une URL par chaîne, il ne doit
 * pas solliciter le Keystore à chaque appui.
 *
 * Le cache est indexé aussi par le format live choisi dans les réglages : changer de format
 * (TS / HLS) s'applique donc dès le zap suivant, sans redémarrer l'appli.
 */
@Singleton
class SourceProvider @Inject constructor(
    private val portalDao: PortalDao,
    private val cipher: CredentialCipher,
    private val factory: IptvSourceFactory,
    private val settings: SettingsRepository,
) {
    private val mutex = Mutex()
    private val cache = HashMap<Pair<Long, String>, IptvSource>()

    suspend fun get(portalId: Long): IptvSource = mutex.withLock {
        val format = settings.playerSettings.value.liveFormat.extension
        cache[portalId to format] ?: run {
            val entity = requireNotNull(portalDao.getById(portalId)) { "Portail $portalId introuvable" }
            val credentials = PortalCredentials(entity.serverUrl, entity.username, cipher.decrypt(entity.encryptedPassword))
            factory.create(PortalType.valueOf(entity.type), credentials, format).also { cache[portalId to format] = it }
        }
    }

    /** À appeler quand les identifiants ou l'URL d'un portail changent, ou qu'il est supprimé. */
    suspend fun invalidate(portalId: Long) = mutex.withLock {
        cache.keys.removeAll { it.first == portalId }
    }
}
