package dev.mytv.domain.usecase

import dev.mytv.domain.model.Category
import dev.mytv.domain.model.ContentKey
import dev.mytv.domain.model.Episode
import dev.mytv.domain.model.Movie
import dev.mytv.domain.model.MovieDetails
import dev.mytv.domain.model.ResumePoint
import dev.mytv.domain.model.SearchResults
import dev.mytv.domain.model.Series
import dev.mytv.domain.model.SeriesDetails
import dev.mytv.domain.model.VodItem
import dev.mytv.domain.repository.VodRepository
import dev.mytv.domain.result.AppResult
import androidx.paging.PagingData
import dev.mytv.domain.model.PosterEntry
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

enum class VodKind { MOVIES, SERIES }

class ObserveVodCategoriesUseCase @Inject constructor(private val repo: VodRepository) {
    operator fun invoke(portalId: Long, kind: VodKind): Flow<List<Category>> = when (kind) {
        VodKind.MOVIES -> repo.observeMovieCategories(portalId)
        VodKind.SERIES -> repo.observeSeriesCategories(portalId)
    }
}

/** Grille paginée de films ou de séries, et son nombre total d'éléments. */
class ObservePosterGridUseCase @Inject constructor(private val repo: VodRepository) {
    fun pages(portalId: Long, kind: VodKind, categoryId: String): Flow<PagingData<PosterEntry>> = when (kind) {
        VodKind.MOVIES -> repo.pagedMovies(portalId, categoryId)
        VodKind.SERIES -> repo.pagedSeries(portalId, categoryId)
    }

    fun count(portalId: Long, kind: VodKind, categoryId: String): Flow<Int> = when (kind) {
        VodKind.MOVIES -> repo.observeMovieCount(portalId, categoryId)
        VodKind.SERIES -> repo.observeSeriesCount(portalId, categoryId)
    }
}

class GetMovieDetailsUseCase @Inject constructor(private val repo: VodRepository) {
    suspend operator fun invoke(portalId: Long, streamId: Long): AppResult<MovieDetails> = repo.getMovieDetails(portalId, streamId)
}

class GetSeriesDetailsUseCase @Inject constructor(private val repo: VodRepository) {
    suspend operator fun invoke(portalId: Long, seriesId: Long): AppResult<SeriesDetails> = repo.getSeriesDetails(portalId, seriesId)
}

class SearchVodUseCase @Inject constructor(private val repo: VodRepository) {
    suspend operator fun invoke(portalId: Long, query: String): SearchResults = repo.search(portalId, query, limit = 60)
}

class GetVodStreamUrlUseCase @Inject constructor(private val repo: VodRepository) {
    suspend operator fun invoke(portalId: Long, item: VodItem): AppResult<String> = repo.streamUrl(portalId, item)
}

/** Lecture des positions enregistrées (fiche, grille, « Continuer à regarder »). */
class ObserveResumeUseCase @Inject constructor(private val repo: VodRepository) {
    fun one(portalId: Long, key: ContentKey): Flow<ResumePoint?> = repo.observeResumePoint(portalId, key)
    fun many(portalId: Long, keys: List<String>): Flow<Map<String, ResumePoint>> = repo.observeResumePoints(portalId, keys)
    fun continueWatching(portalId: Long): Flow<List<ResumePoint>> = repo.observeContinueWatching(portalId, limit = 20)

    /** Retire un contenu de « Continuer à regarder » / « Accès rapide » (efface sa position). */
    suspend fun remove(portalId: Long, storageKey: String) {
        ContentKey.fromStorageKey(storageKey)?.let { repo.clearResumePoint(portalId, it) }
    }
}

/** Synchronise le catalogue VOD s'il est périmé (ou si [force]). */
class SyncVodUseCase @Inject constructor(private val repo: VodRepository) {
    suspend operator fun invoke(portalId: Long, force: Boolean = false): AppResult<Unit> =
        if (force || repo.isCatalogStale(portalId, MAX_AGE_MS)) repo.refreshCatalog(portalId) else AppResult.Success(Unit)

    private companion object {
        /** Le catalogue VOD bouge peu : une synchro par jour suffit (bouton Actualiser sinon). */
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}

/**
 * Règles de reprise de lecture, centralisées ici pour être testées :
 * - en dessous de [MIN_POSITION_MS], inutile de proposer une reprise ;
 * - au-delà de [WATCHED_RATIO] de la durée (générique de fin), le contenu est « vu » :
 *   la position est effacée et il sort de « Continuer à regarder ».
 */
object ResumePolicy {
    const val MIN_POSITION_MS = 30_000L
    const val WATCHED_RATIO = 0.95

    fun isWatched(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs >= durationMs * WATCHED_RATIO

    /** Position à laquelle reprendre, ou 0 pour partir du début. */
    fun startPosition(point: ResumePoint?): Long =
        if (point == null || point.positionMs < MIN_POSITION_MS || isWatched(point.positionMs, point.durationMs)) 0L
        else point.positionMs
}

/** Enregistre la position courante selon [ResumePolicy]. */
class SaveProgressUseCase @Inject constructor(private val repo: VodRepository) {
    suspend operator fun invoke(portalId: Long, item: VodItem, positionMs: Long, durationMs: Long) {
        when {
            ResumePolicy.isWatched(positionMs, durationMs) -> repo.clearResumePoint(portalId, item.key)
            positionMs >= ResumePolicy.MIN_POSITION_MS -> repo.saveResumePoint(portalId, item, positionMs, durationMs)
        }
    }

    /**
     * Épisode terminé : l'épisode suivant devient « à suivre » (position 0). La fiche de la série
     * et « Continuer à regarder » proposent alors directement la suite, et non le premier épisode.
     */
    suspend fun markNextUp(portalId: Long, next: VodItem) = repo.saveResumePoint(portalId, next, 0, 0)
}

/** Épisode suivant dans la série (y compris en passant à la saison suivante), ou null. */
fun SeriesDetails.nextEpisodeAfter(episodeId: Long): Episode? {
    val all = seasons.flatMap { it.episodes }
    val index = all.indexOfFirst { it.id == episodeId }
    return if (index >= 0) all.getOrNull(index + 1) else null
}

fun Movie.toVodItem() = VodItem(
    key = ContentKey.MovieKey(streamId),
    title = name,
    subtitle = null,
    imageUrl = posterUrl,
    containerExtension = containerExtension,
)

fun Episode.toVodItem(seriesName: String, seriesCover: String?) = VodItem(
    key = ContentKey.EpisodeKey(id),
    title = seriesName,
    subtitle = "S${season.toString().padStart(2, '0')}E${number.toString().padStart(2, '0')} · $title",
    imageUrl = imageUrl ?: seriesCover,
    containerExtension = containerExtension,
    seriesId = seriesId,
)
