package dev.mytv.domain.repository

import dev.mytv.domain.model.Category
import dev.mytv.domain.model.ContentKey
import dev.mytv.domain.model.Movie
import dev.mytv.domain.model.MovieDetails
import dev.mytv.domain.model.ResumePoint
import dev.mytv.domain.model.SearchResults
import dev.mytv.domain.model.Series
import dev.mytv.domain.model.SeriesDetails
import dev.mytv.domain.model.VodItem
import dev.mytv.domain.result.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Films et séries. Comme pour la Live TV, les listes viennent du cache Room (affichage instantané,
 * hors ligne) ; les fiches détaillées sont demandées au portail à l'ouverture.
 */
interface VodRepository {
    /** Catégories de films, précédées de « Tous les films ». */
    fun observeMovieCategories(portalId: Long): Flow<List<Category>>
    fun observeMovies(portalId: Long, categoryId: String): Flow<List<Movie>>

    /** Catégories de séries, précédées de « Toutes les séries ». */
    fun observeSeriesCategories(portalId: Long): Flow<List<Category>>
    fun observeSeries(portalId: Long, categoryId: String): Flow<List<Series>>

    suspend fun isCatalogStale(portalId: Long, maxAgeMs: Long): Boolean

    /** Télécharge catégories, films et séries et remplace le cache (positions conservées). */
    suspend fun refreshCatalog(portalId: Long): AppResult<Unit>

    suspend fun getMovieDetails(portalId: Long, streamId: Long): AppResult<MovieDetails>
    suspend fun getSeriesDetails(portalId: Long, seriesId: Long): AppResult<SeriesDetails>

    /** Recherche insensible à la casse et aux accents dans les films et les séries en cache. */
    suspend fun search(portalId: Long, query: String, limit: Int): SearchResults

    /** URL de lecture (contient les identifiants : ne jamais la logger). */
    suspend fun streamUrl(portalId: Long, item: VodItem): AppResult<String>

    fun observeResumePoint(portalId: Long, key: ContentKey): Flow<ResumePoint?>
    fun observeResumePoints(portalId: Long, keys: List<String>): Flow<Map<String, ResumePoint>>
    fun observeContinueWatching(portalId: Long, limit: Int): Flow<List<ResumePoint>>
    suspend fun saveResumePoint(portalId: Long, item: VodItem, positionMs: Long, durationMs: Long)
    suspend fun clearResumePoint(portalId: Long, key: ContentKey)
}
