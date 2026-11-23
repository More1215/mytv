package dev.mytv.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PortalDao {
    @Query("SELECT * FROM portals WHERE id = :id")
    fun observeById(id: Long): Flow<PortalEntity?>

    @Query("SELECT * FROM portals WHERE id = :id")
    suspend fun getById(id: Long): PortalEntity?

    @Query("SELECT * FROM portals WHERE serverUrl = :serverUrl AND username = :username")
    suspend fun findByLogin(serverUrl: String, username: String): PortalEntity?

    @Query("SELECT * FROM portals ORDER BY createdAtEpochMs LIMIT 1")
    suspend fun getFirst(): PortalEntity?

    @Upsert
    suspend fun upsert(portal: PortalEntity): Long

    @Query("UPDATE portals SET serverUrl = :serverUrl WHERE id = :id")
    suspend fun updateServerUrl(id: Long, serverUrl: String)

    @Query("SELECT * FROM portals ORDER BY createdAtEpochMs")
    fun observeAll(): Flow<List<PortalEntity>>

    @Query("SELECT * FROM portals ORDER BY createdAtEpochMs")
    suspend fun getAll(): List<PortalEntity>

    // --- Suppression complète d'un portail : toutes ses tables de cache et de données.
    @Query("DELETE FROM portals WHERE id = :id") suspend fun deletePortalRow(id: Long)
    @Query("DELETE FROM live_categories WHERE portalId = :id") suspend fun deleteLiveCategories(id: Long)
    @Query("DELETE FROM live_channels WHERE portalId = :id") suspend fun deleteLiveChannels(id: Long)
    @Query("DELETE FROM live_favorites WHERE portalId = :id") suspend fun deleteFavorites(id: Long)
    @Query("DELETE FROM epg_programs WHERE portalId = :id") suspend fun deleteEpg(id: Long)
    @Query("DELETE FROM vod_categories WHERE portalId = :id") suspend fun deleteVodCategories(id: Long)
    @Query("DELETE FROM movies WHERE portalId = :id") suspend fun deleteMovies(id: Long)
    @Query("DELETE FROM series WHERE portalId = :id") suspend fun deleteSeries(id: Long)
    @Query("DELETE FROM resume_points WHERE portalId = :id") suspend fun deleteResumePoints(id: Long)

    @Transaction
    suspend fun deleteEverything(id: Long) {
        deleteLiveCategories(id); deleteLiveChannels(id); deleteFavorites(id); deleteEpg(id)
        deleteVodCategories(id); deleteMovies(id); deleteSeries(id); deleteResumePoints(id)
        deletePortalRow(id)
    }
}
