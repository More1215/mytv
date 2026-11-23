package dev.mytv.domain.usecase

import dev.mytv.domain.repository.AppUpdate
import dev.mytv.domain.repository.UpdateRepository
import dev.mytv.domain.result.AppResult
import javax.inject.Inject

/** Cherche une version plus récente que [currentVersion]. null = l'appli est à jour (ou rien trouvé). */
class CheckForUpdateUseCase @Inject constructor(private val repo: UpdateRepository) {
    suspend operator fun invoke(currentVersion: String): AppResult<AppUpdate?> =
        when (val r = repo.latest()) {
            is AppResult.Failure -> r
            is AppResult.Success -> AppResult.Success(r.value?.takeIf { VersionCompare.isNewer(it.version, currentVersion) })
        }
}

class DownloadUpdateUseCase @Inject constructor(private val repo: UpdateRepository) {
    suspend operator fun invoke(update: AppUpdate, onProgress: (Float) -> Unit): AppResult<String> =
        repo.download(update, onProgress)
}

/** Comparaison de versions « 1.0.10 » > « 1.0.9 » (numérique, champ par champ ; suffixes ignorés). */
object VersionCompare {
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parts(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
}
