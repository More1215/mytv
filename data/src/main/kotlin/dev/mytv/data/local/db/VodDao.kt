package dev.mytv.data.local.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Case de grille de film + position de lecture éventuelle (jointure sur la clé "movie:<id>"). */
private const val MOVIE_POSTER = """
    SELECT m.streamId AS id, m.name AS title, m.posterUrl AS imageUrl,
           r.positionMs AS positionMs, r.durationMs AS durationMs
    FROM movies m
    LEFT JOIN resume_points r ON r.portalId = m.portalId AND r.contentKey = 'movie:' || m.streamId
"""

data class MoviePosterRow(val id: Long, val title: String, val imageUrl: String?, val positionMs: Long?, val durationMs: Long?)

data class SeriesPosterRow(val id: Long, val title: String, val imageUrl: String?)

@Dao
interface VodDao {
    // ------------------------------------------------------------------ Catalogue

    @Query("SELECT * FROM vod_categories WHERE portalId = :portalId AND kind = :kind ORDER BY sortOrder")
    fun observeCategories(portalId: Long, kind: String): Flow<List<VodCategoryEntity>>

    // Grilles paginées : seules 3 colonnes sont lues (pas de ligne complète), page par page, et la
    // progression de lecture est jointe en SQL (aucune requête « IN (15 000 clés) », qui dépasse
    // la limite de 999 paramètres de SQLite sur les Android anciens).

    @Query("$MOVIE_POSTER WHERE m.portalId = :portalId ORDER BY m.sortOrder")
    fun pagedAllMovies(portalId: Long): PagingSource<Int, MoviePosterRow>

    @Query("$MOVIE_POSTER WHERE m.portalId = :portalId AND m.categoryId = :categoryId ORDER BY m.sortOrder")
    fun pagedMovies(portalId: Long, categoryId: String): PagingSource<Int, MoviePosterRow>

    @Query("SELECT seriesId AS id, name AS title, coverUrl AS imageUrl FROM series WHERE portalId = :portalId ORDER BY sortOrder")
    fun pagedAllSeries(portalId: Long): PagingSource<Int, SeriesPosterRow>

    @Query(
        "SELECT seriesId AS id, name AS title, coverUrl AS imageUrl FROM series " +
            "WHERE portalId = :portalId AND categoryId = :categoryId ORDER BY sortOrder",
    )
    fun pagedSeries(portalId: Long, categoryId: String): PagingSource<Int, SeriesPosterRow>

    @Query("SELECT COUNT(*) FROM movies WHERE portalId = :portalId")
    fun observeAllMoviesCount(portalId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM movies WHERE portalId = :portalId AND categoryId = :categoryId")
    fun observeMoviesCount(portalId: Long, categoryId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM series WHERE portalId = :portalId")
    fun observeAllSeriesCount(portalId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM series WHERE portalId = :portalId AND categoryId = :categoryId")
    fun observeSeriesCount(portalId: Long, categoryId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM movies WHERE portalId = :portalId")
    suspend fun countMovies(portalId: Long): Int

    @Query("SELECT COUNT(*) FROM series WHERE portalId = :portalId")
    suspend fun countSeries(portalId: Long): Int

    /** Recherche : les titres qui COMMENCENT par la saisie d'abord, puis ceux qui la contiennent. */
    @Query(
        """
        SELECT * FROM movies WHERE portalId = :portalId AND searchName LIKE '%' || :query || '%'
        ORDER BY CASE WHEN searchName LIKE :query || '%' THEN 0 ELSE 1 END, name LIMIT :limit
        """,
    )
    suspend fun searchMovies(portalId: Long, query: String, limit: Int): List<MovieEntity>

    @Query(
        """
        SELECT * FROM series WHERE portalId = :portalId AND searchName LIKE '%' || :query || '%'
        ORDER BY CASE WHEN searchName LIKE :query || '%' THEN 0 ELSE 1 END, name LIMIT :limit
        """,
    )
    suspend fun searchSeries(portalId: Long, query: String, limit: Int): List<SeriesEntity>

    @Query("DELETE FROM vod_categories WHERE portalId = :portalId")
    suspend fun deleteCategories(portalId: Long)

    @Query("DELETE FROM movies WHERE portalId = :portalId")
    suspend fun deleteMovies(portalId: Long)

    @Query("DELETE FROM series WHERE portalId = :portalId")
    suspend fun deleteSeries(portalId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(items: List<VodCategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMovies(items: List<MovieEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeries(items: List<SeriesEntity>)

    /** Remplacement atomique du catalogue VOD (les positions de lecture ne sont pas touchées). */
    @Transaction
    suspend fun replaceCatalog(
        portalId: Long,
        categories: List<VodCategoryEntity>,
        movies: List<MovieEntity>,
        series: List<SeriesEntity>,
    ) {
        deleteCategories(portalId)
        deleteMovies(portalId)
        deleteSeries(portalId)
        insertCategories(categories)
        movies.chunked(500).forEach { insertMovies(it) }
        series.chunked(500).forEach { insertSeries(it) }
    }

    // ------------------------------------------------------------------ Reprise de lecture

    @Query("SELECT * FROM resume_points WHERE portalId = :portalId AND contentKey = :key")
    fun observeResume(portalId: Long, key: String): Flow<ResumeEntity?>

    @Query("SELECT * FROM resume_points WHERE portalId = :portalId AND contentKey IN (:keys)")
    fun observeResumes(portalId: Long, keys: List<String>): Flow<List<ResumeEntity>>

    @Query("SELECT * FROM resume_points WHERE portalId = :portalId ORDER BY updatedAtMs DESC LIMIT :limit")
    fun observeRecentResumes(portalId: Long, limit: Int): Flow<List<ResumeEntity>>

    @Upsert
    suspend fun upsertResume(item: ResumeEntity)

    @Query("DELETE FROM resume_points WHERE portalId = :portalId AND contentKey = :key")
    suspend fun deleteResume(portalId: Long, key: String)

    /** Une seule entrée par série dans « Continuer à regarder » : on retire les autres épisodes. */
    @Query("DELETE FROM resume_points WHERE portalId = :portalId AND seriesId = :seriesId AND contentKey != :keepKey")
    suspend fun deleteOtherEpisodes(portalId: Long, seriesId: Long, keepKey: String)
}
