package dev.mytv.data.repository

import dev.mytv.data.error.runCatchingApp
import dev.mytv.data.local.db.MovieEntity
import dev.mytv.data.local.db.ResumeEntity
import dev.mytv.data.local.db.SeriesEntity
import dev.mytv.data.local.db.VodCategoryEntity
import dev.mytv.data.local.db.VodCategoryEntity.Companion.KIND_MOVIE
import dev.mytv.data.local.db.VodCategoryEntity.Companion.KIND_SERIES
import dev.mytv.data.local.db.VodDao
import dev.mytv.data.local.db.searchKey
import dev.mytv.data.local.prefs.AppPreferences
import dev.mytv.domain.model.Category
import dev.mytv.domain.model.ContentKey
import dev.mytv.domain.model.Movie
import dev.mytv.domain.model.MovieDetails
import dev.mytv.domain.model.ResumePoint
import dev.mytv.domain.model.SearchResults
import dev.mytv.domain.model.Series
import dev.mytv.domain.model.SeriesDetails
import dev.mytv.domain.model.VodItem
import dev.mytv.domain.repository.VodRepository
import dev.mytv.domain.result.AppResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class VodRepositoryImpl @Inject constructor(
    private val vodDao: VodDao,
    private val preferences: AppPreferences,
    private val sources: SourceProvider,
    @param:Named(PortalRepositoryImpl.IO_DISPATCHER) private val io: CoroutineDispatcher,
) : VodRepository {

    private val syncMutex = Mutex()

    /** Fiches déjà ouvertes pendant la session : revenir sur une fiche est instantané. */
    private val movieDetailsCache = ConcurrentHashMap<Pair<Long, Long>, MovieDetails>()
    private val seriesDetailsCache = ConcurrentHashMap<Pair<Long, Long>, SeriesDetails>()

    // ------------------------------------------------------------------ Catalogue

    override fun observeMovieCategories(portalId: Long): Flow<List<Category>> =
        vodDao.observeCategories(portalId, KIND_MOVIE).map { list ->
            listOf(Category(Category.ALL_ID, "Tous les films", Category.Kind.ALL)) + list.map { Category(it.categoryId, it.name) }
        }

    override fun observeMovies(portalId: Long, categoryId: String): Flow<List<Movie>> =
        (if (categoryId == Category.ALL_ID) vodDao.observeAllMovies(portalId) else vodDao.observeMovies(portalId, categoryId))
            .map { list -> list.map { it.toDomain() } }
            .flowOn(io)

    override fun observeSeriesCategories(portalId: Long): Flow<List<Category>> =
        vodDao.observeCategories(portalId, KIND_SERIES).map { list ->
            listOf(Category(Category.ALL_ID, "Toutes les séries", Category.Kind.ALL)) + list.map { Category(it.categoryId, it.name) }
        }

    override fun observeSeries(portalId: Long, categoryId: String): Flow<List<Series>> =
        (if (categoryId == Category.ALL_ID) vodDao.observeAllSeries(portalId) else vodDao.observeSeries(portalId, categoryId))
            .map { list -> list.map { it.toDomain() } }
            .flowOn(io)

    override suspend fun isCatalogStale(portalId: Long, maxAgeMs: Long): Boolean = withContext(io) {
        val last = preferences.lastVodSync(portalId)
        last == null || System.currentTimeMillis() - last > maxAgeMs ||
            (vodDao.countMovies(portalId) == 0 && vodDao.countSeries(portalId) == 0)
    }

    override suspend fun refreshCatalog(portalId: Long): AppResult<Unit> = withContext(io) {
        syncMutex.withLock {
            runCatchingApp {
                val source = sources.get(portalId)
                // Les 4 listes sont indépendantes : téléchargement en parallèle.
                coroutineScope {
                    val movieCats = async { source.movieCategories() }
                    val seriesCats = async { source.seriesCategories() }
                    val movies = async { source.movies() }
                    val series = async { source.series() }

                    val categories =
                        movieCats.await().mapIndexed { i, c -> VodCategoryEntity(portalId, KIND_MOVIE, c.id, c.name, i) } +
                            seriesCats.await().mapIndexed { i, c -> VodCategoryEntity(portalId, KIND_SERIES, c.id, c.name, i) }
                    val movieEntities = movies.await().mapIndexed { i, m ->
                        MovieEntity(portalId, m.streamId, m.name, searchKey(m.name), m.posterUrl, m.rating, m.categoryId, m.extension, m.addedAtEpochSec, i)
                    }
                    val seriesEntities = series.await().mapIndexed { i, s ->
                        SeriesEntity(portalId, s.seriesId, s.name, searchKey(s.name), s.coverUrl, s.rating, s.categoryId, i)
                    }
                    vodDao.replaceCatalog(portalId, categories, movieEntities, seriesEntities)
                }
                preferences.setLastVodSync(portalId, System.currentTimeMillis())
            }
        }
    }

    override suspend fun getMovieDetails(portalId: Long, streamId: Long): AppResult<MovieDetails> = withContext(io) {
        movieDetailsCache[portalId to streamId]?.let { return@withContext AppResult.Success(it) }
        runCatchingApp {
            sources.get(portalId).movieDetails(streamId).also { movieDetailsCache[portalId to streamId] = it }
        }
    }

    override suspend fun getSeriesDetails(portalId: Long, seriesId: Long): AppResult<SeriesDetails> = withContext(io) {
        seriesDetailsCache[portalId to seriesId]?.let { return@withContext AppResult.Success(it) }
        runCatchingApp {
            sources.get(portalId).seriesDetails(seriesId).also { seriesDetailsCache[portalId to seriesId] = it }
        }
    }

    override suspend fun search(portalId: Long, query: String, limit: Int): SearchResults = withContext(io) {
        // Les jokers SQL saisis (% et _) sont retirés pour qu'ils ne faussent pas le LIKE.
        val key = searchKey(query).replace("%", "").replace("_", " ").trim()
        if (key.length < 2) return@withContext SearchResults.EMPTY
        SearchResults(
            movies = vodDao.searchMovies(portalId, key, limit).map { it.toDomain() },
            series = vodDao.searchSeries(portalId, key, limit).map { it.toDomain() },
        )
    }

    override suspend fun streamUrl(portalId: Long, item: VodItem): AppResult<String> = runCatchingApp {
        val source = sources.get(portalId)
        when (val key = item.key) {
            is ContentKey.MovieKey -> source.movieUrl(key.streamId, item.containerExtension)
            is ContentKey.EpisodeKey -> source.episodeUrl(key.episodeId, item.containerExtension)
        }
    }

    // ------------------------------------------------------------------ Reprise de lecture

    override fun observeResumePoint(portalId: Long, key: ContentKey): Flow<ResumePoint?> =
        vodDao.observeResume(portalId, key.storageKey).map { it?.toDomain() }.flowOn(io)

    override fun observeResumePoints(portalId: Long, keys: List<String>): Flow<Map<String, ResumePoint>> =
        vodDao.observeResumes(portalId, keys.take(900)).map { list -> list.associate { it.contentKey to it.toDomain() } }.flowOn(io)

    override fun observeContinueWatching(portalId: Long, limit: Int): Flow<List<ResumePoint>> =
        vodDao.observeRecentResumes(portalId, limit).map { list -> list.map { it.toDomain() } }.flowOn(io)

    override suspend fun saveResumePoint(portalId: Long, item: VodItem, positionMs: Long, durationMs: Long) {
        withContext(io) {
            val key = item.key.storageKey
            vodDao.upsertResume(
                ResumeEntity(portalId, key, positionMs, durationMs, item.title, item.subtitle, item.imageUrl, item.seriesId, System.currentTimeMillis()),
            )
            // Une seule entrée par série dans « Continuer à regarder » : l'épisode le plus récent.
            item.seriesId?.let { vodDao.deleteOtherEpisodes(portalId, it, keepKey = key) }
        }
    }

    override suspend fun clearResumePoint(portalId: Long, key: ContentKey) = withContext(io) {
        vodDao.deleteResume(portalId, key.storageKey)
    }
}

private fun MovieEntity.toDomain() = Movie(streamId, name, posterUrl, rating, categoryId, extension, addedAtEpochSec)

private fun SeriesEntity.toDomain() = Series(seriesId, name, coverUrl, rating, categoryId)

private fun ResumeEntity.toDomain() = ResumePoint(contentKey, positionMs, durationMs, title, subtitle, imageUrl, seriesId, updatedAtMs)
