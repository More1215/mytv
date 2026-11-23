package dev.mytv.domain.usecase

import dev.mytv.domain.model.Portal
import dev.mytv.domain.model.PlayerSettings
import dev.mytv.domain.repository.BackupRepository
import dev.mytv.domain.repository.PortalRepository
import dev.mytv.domain.repository.RestoreSummary
import dev.mytv.domain.repository.SettingsRepository
import dev.mytv.domain.result.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class ObservePlayerSettingsUseCase @Inject constructor(private val repo: SettingsRepository) {
    operator fun invoke(): StateFlow<PlayerSettings> = repo.playerSettings
}

class UpdatePlayerSettingsUseCase @Inject constructor(private val repo: SettingsRepository) {
    suspend operator fun invoke(transform: (PlayerSettings) -> PlayerSettings) = repo.update(transform)
}

class ObservePortalsUseCase @Inject constructor(private val repo: PortalRepository) {
    operator fun invoke(): Flow<List<Portal>> = repo.observePortals()
}

class SwitchPortalUseCase @Inject constructor(private val repo: PortalRepository) {
    suspend operator fun invoke(portalId: Long) = repo.setActivePortal(portalId)
}

class DeletePortalUseCase @Inject constructor(private val repo: PortalRepository) {
    suspend operator fun invoke(portalId: Long) = repo.deletePortal(portalId)
}

/**
 * Sauvegarde et restauration. La phrase secrète doit faire au moins [MIN_PASSPHRASE] caractères :
 * c'est elle seule qui protège les mots de passe des portails contenus dans le fichier.
 */
class BackupUseCase @Inject constructor(private val repo: BackupRepository) {
    fun isPassphraseValid(passphrase: String): Boolean = passphrase.length >= MIN_PASSPHRASE

    suspend fun export(passphrase: String): AppResult<ByteArray>? =
        if (isPassphraseValid(passphrase)) repo.export(passphrase) else null

    suspend fun restore(content: ByteArray, passphrase: String): AppResult<RestoreSummary> =
        repo.restore(content, passphrase)

    companion object {
        const val MIN_PASSPHRASE = 8
    }
}
