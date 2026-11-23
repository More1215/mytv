package dev.mytv.data.local.db

import androidx.room.Dao
import androidx.room.Query
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
}
