package dev.mytv.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Accès groupés pour la sauvegarde / restauration (tous portails confondus). */
@Dao
interface BackupDao {
    @Query("SELECT * FROM live_favorites")
    suspend fun allFavorites(): List<LiveFavoriteEntity>

    @Query("SELECT * FROM resume_points")
    suspend fun allResumePoints(): List<ResumeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorites(items: List<LiveFavoriteEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertResumePoints(items: List<ResumeEntity>)
}
