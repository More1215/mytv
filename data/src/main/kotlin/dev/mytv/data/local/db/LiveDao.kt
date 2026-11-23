package dev.mytv.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Colonnes communes de la jointure chaîne + favori. */
private const val SELECT_CHANNEL = """
    SELECT c.streamId, c.number, c.name, c.logoUrl, c.categoryId, c.epgChannelId,
           (f.streamId IS NOT NULL) AS isFavorite
    FROM live_channels c
    LEFT JOIN live_favorites f ON f.portalId = c.portalId AND f.streamId = c.streamId
"""

@Dao
interface LiveDao {
    @Query("SELECT * FROM live_categories WHERE portalId = :portalId ORDER BY sortOrder")
    fun observeCategories(portalId: Long): Flow<List<LiveCategoryEntity>>

    @Query("$SELECT_CHANNEL WHERE c.portalId = :portalId AND c.categoryId = :categoryId ORDER BY c.sortOrder")
    fun observeChannelsInCategory(portalId: Long, categoryId: String): Flow<List<ChannelWithFavorite>>

    @Query("$SELECT_CHANNEL WHERE c.portalId = :portalId ORDER BY c.sortOrder")
    fun observeAllChannels(portalId: Long): Flow<List<ChannelWithFavorite>>

    @Query("$SELECT_CHANNEL WHERE c.portalId = :portalId AND f.streamId IS NOT NULL ORDER BY f.addedAtMs")
    fun observeFavoriteChannels(portalId: Long): Flow<List<ChannelWithFavorite>>

    @Query("$SELECT_CHANNEL WHERE c.portalId = :portalId AND c.streamId = :streamId")
    suspend fun getChannel(portalId: Long, streamId: Long): ChannelWithFavorite?

    @Query("SELECT COUNT(*) FROM live_channels WHERE portalId = :portalId")
    suspend fun countChannels(portalId: Long): Int

    @Query("DELETE FROM live_categories WHERE portalId = :portalId")
    suspend fun deleteCategories(portalId: Long)

    @Query("DELETE FROM live_channels WHERE portalId = :portalId")
    suspend fun deleteChannels(portalId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(items: List<LiveCategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(items: List<LiveChannelEntity>)

    /** Remplacement atomique : l'UI ne voit jamais un catalogue à moitié écrit. */
    @Transaction
    suspend fun replaceCatalog(portalId: Long, categories: List<LiveCategoryEntity>, channels: List<LiveChannelEntity>) {
        deleteCategories(portalId)
        deleteChannels(portalId)
        insertCategories(categories)
        channels.chunked(500).forEach { insertChannels(it) }
    }

    // ------------------------------------------------------------------ Chaînes les plus regardées

    @Query("INSERT OR IGNORE INTO channel_stats (portalId, streamId, playCount, lastPlayedMs) VALUES (:portalId, :streamId, 0, :nowMs)")
    suspend fun ensureStat(portalId: Long, streamId: Long, nowMs: Long)

    @Query("UPDATE channel_stats SET playCount = playCount + 1, lastPlayedMs = :nowMs WHERE portalId = :portalId AND streamId = :streamId")
    suspend fun bumpStat(portalId: Long, streamId: Long, nowMs: Long)

    @Transaction
    suspend fun recordWatch(portalId: Long, streamId: Long, nowMs: Long) {
        ensureStat(portalId, streamId, nowMs)
        bumpStat(portalId, streamId, nowMs)
    }

    @Query("DELETE FROM channel_stats WHERE portalId = :portalId AND streamId = :streamId")
    suspend fun deleteStat(portalId: Long, streamId: Long)

    /** Les plus regardées d'abord ; à égalité, la plus récente. Chaînes disparues du catalogue ignorées. */
    @Query(
        """$SELECT_CHANNEL
        JOIN channel_stats s ON s.portalId = c.portalId AND s.streamId = c.streamId
        WHERE c.portalId = :portalId
        ORDER BY s.playCount DESC, s.lastPlayedMs DESC LIMIT :limit""",
    )
    fun observeMostWatched(portalId: Long, limit: Int): Flow<List<ChannelWithFavorite>>

    @Query("SELECT EXISTS(SELECT 1 FROM live_favorites WHERE portalId = :portalId AND streamId = :streamId)")
    suspend fun isFavorite(portalId: Long, streamId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: LiveFavoriteEntity)

    @Query("DELETE FROM live_favorites WHERE portalId = :portalId AND streamId = :streamId")
    suspend fun deleteFavorite(portalId: Long, streamId: Long)
}

@Dao
interface EpgDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(items: List<EpgProgramEntity>)

    /** Programmes qui chevauchent [fromMs, toMs[ pour les chaînes demandées (≤ 900 ids par appel). */
    @Query(
        """
        SELECT * FROM epg_programs
        WHERE portalId = :portalId AND generation = :generation
          AND epgChannelId IN (:channelIds) AND endMs > :fromMs AND startMs < :toMs
        ORDER BY epgChannelId, startMs
        """,
    )
    suspend fun programs(portalId: Long, generation: Long, channelIds: List<String>, fromMs: Long, toMs: Long): List<EpgProgramEntity>

    /** Purge des anciennes générations (après bascule) et du passé lointain. */
    @Query("DELETE FROM epg_programs WHERE portalId = :portalId AND (generation != :keepGeneration OR endMs < :olderThanMs)")
    suspend fun deleteObsolete(portalId: Long, keepGeneration: Long, olderThanMs: Long)
}
