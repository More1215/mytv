package dev.mytv.data.local.db

import androidx.room.Entity
import androidx.room.Index

/*
 * Toutes les tables de cache sont préfixées par portalId : chaque portail a son propre catalogue,
 * ce qui rend le multi-portails (étape 3) trivial et permet de tout purger par portail.
 */

@Entity(tableName = "live_categories", primaryKeys = ["portalId", "categoryId"])
data class LiveCategoryEntity(
    val portalId: Long,
    val categoryId: String,
    val name: String,
    /** Ordre d'origine du portail (les revendeurs ordonnent leurs catégories volontairement). */
    val sortOrder: Int,
)

@Entity(
    tableName = "live_channels",
    primaryKeys = ["portalId", "streamId"],
    indices = [
        Index(value = ["portalId", "categoryId", "sortOrder"]),
        Index(value = ["portalId", "number"]),
        Index(value = ["portalId", "sortOrder"]),
    ],
)
data class LiveChannelEntity(
    val portalId: Long,
    val streamId: Long,
    val number: Int,
    val name: String,
    val logoUrl: String?,
    val categoryId: String?,
    val epgChannelId: String?,
    val sortOrder: Int,
)

/** Table séparée du catalogue : une resynchronisation (qui remplace les chaînes) ne perd pas les favoris. */
@Entity(tableName = "live_favorites", primaryKeys = ["portalId", "streamId"])
data class LiveFavoriteEntity(
    val portalId: Long,
    val streamId: Long,
    val addedAtMs: Long,
)

/**
 * Programme du guide. [generation] permet d'importer un nouveau guide pendant que l'ancien reste
 * affiché, puis de basculer d'un coup (voir LiveRepositoryImpl.refreshEpg).
 */
@Entity(
    tableName = "epg_programs",
    primaryKeys = ["portalId", "generation", "epgChannelId", "startMs"],
    indices = [Index(value = ["portalId", "generation", "epgChannelId", "endMs"])],
)
data class EpgProgramEntity(
    val portalId: Long,
    val generation: Long,
    val epgChannelId: String,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val description: String?,
)

/** Projection chaîne + favori, produite par une jointure. */
data class ChannelWithFavorite(
    val streamId: Long,
    val number: Int,
    val name: String,
    val logoUrl: String?,
    val categoryId: String?,
    val epgChannelId: String?,
    val isFavorite: Boolean,
)
