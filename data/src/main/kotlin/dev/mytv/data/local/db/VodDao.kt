package dev.mytv.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface VodDao {
    // ------------------------------------------------------------------ Catalogue

    @Query("SELECT * FROM vod_categories WHERE portalId = :portalId AND kind = :kind ORDER BY sortOrder")
    fun observeCategories(portalId: Long, kind: String): Flow<List<VodCategoryEntity>>

    @Query("SELECT * FROM movies WHERE portalId = :portalId ORDER BY sortOrder")
    fun observeAllMovies(portalId: Long): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE portalId = :portalId AND categoryId = :categoryId ORDER BY sortOrder")
    fun observeMovies(portalId: Long, categoryId: String): Flow<List<MovieEntity>>

    @Query("SELECT * FROM series WHERE portalId = :portalId ORDER BY sortOrder")
    fun observeAllSeries(portalId: Long): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE portalId = :portalId AND categoryId = :categoryId ORDER BY sortOrder")
    fun observeSeries(portalId: Long, categoryId: String): Flow<List<SeriesEntity>>

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
