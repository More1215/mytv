package dev.mytv.domain.repository

import dev.mytv.domain.result.AppResult

/** Ce qu'une restauration a importé, pour l'afficher à l'utilisateur. */
data class RestoreSummary(val portals: Int, val favorites: Int, val resumePoints: Int)

/**
 * Sauvegarde complète : portails (mots de passe compris), favoris, positions de lecture, réglages.
 * Le contenu est TOUJOURS chiffré avec une phrase secrète choisie par l'utilisateur : il contient
 * les mots de passe des portails et peut se retrouver sur un PC ou un téléphone.
 */
interface BackupRepository {
    suspend fun export(passphrase: String): AppResult<ByteArray>
    suspend fun restore(content: ByteArray, passphrase: String): AppResult<RestoreSummary>
}
