package dev.mytv.domain.repository

import dev.mytv.domain.result.AppResult

/** Dernière version publiée de l'appli. */
data class AppUpdate(
    /** Ex. "1.0.4" (sans le « v » de l'étiquette GitHub). */
    val version: String,
    val downloadUrl: String,
    val notes: String?,
)

/** Mises à jour de l'appli elle-même (publiées sur GitHub Releases). */
interface UpdateRepository {
    /** Dernière version publiée, ou null si aucune n'est trouvée. */
    suspend fun latest(): AppResult<AppUpdate?>

    /**
     * Télécharge l'APK dans le cache de l'appli et renvoie le chemin du fichier.
     * [onProgress] reçoit l'avancement entre 0 et 1 (ou -1 si la taille est inconnue).
     */
    suspend fun download(update: AppUpdate, onProgress: (Float) -> Unit): AppResult<String>
}
