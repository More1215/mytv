package dev.mytv.data.local.db

import androidx.room.Entity
import androidx.room.Index
import java.text.Normalizer

/** Catégorie de films ([KIND_MOVIE]) ou de séries ([KIND_SERIES]). */
@Entity(tableName = "vod_categories", primaryKeys = ["portalId", "kind", "categoryId"])
data class VodCategoryEntity(
    val portalId: Long,
    val kind: String,
    val categoryId: String,
    val name: String,
    val sortOrder: Int,
) {
    companion object {
        const val KIND_MOVIE = "movie"
        const val KIND_SERIES = "series"
    }
}

@Entity(
    tableName = "movies",
    primaryKeys = ["portalId", "streamId"],
    indices = [Index(value = ["portalId", "categoryId", "sortOrder"]), Index(value = ["portalId", "sortOrder"])],
)
data class MovieEntity(
    val portalId: Long,
    val streamId: Long,
    val name: String,
    /** Nom normalisé (minuscules, sans accents) pour une recherche rapide et tolérante. */
    val searchName: String,
    val posterUrl: String?,
    val rating: Double?,
    val categoryId: String?,
    val extension: String,
    val addedAtEpochSec: Long?,
    val sortOrder: Int,
)

@Entity(
    tableName = "series",
    primaryKeys = ["portalId", "seriesId"],
    indices = [Index(value = ["portalId", "categoryId", "sortOrder"]), Index(value = ["portalId", "sortOrder"])],
)
data class SeriesEntity(
    val portalId: Long,
    val seriesId: Long,
    val name: String,
    val searchName: String,
    val coverUrl: String?,
    val rating: Double?,
    val categoryId: String?,
    val sortOrder: Int,
)

/**
 * Position de lecture d'un film ou d'un épisode. Table indépendante du catalogue : une
 * resynchronisation ne fait pas perdre les reprises. Titre et image sont copiés ici pour afficher
 * « Continuer à regarder » sans dépendre du catalogue (un épisode n'y figure pas).
 */
@Entity(
    tableName = "resume_points",
    primaryKeys = ["portalId", "contentKey"],
    indices = [Index(value = ["portalId", "updatedAtMs"])],
)
data class ResumeEntity(
    val portalId: Long,
    val contentKey: String,
    val positionMs: Long,
    val durationMs: Long,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val seriesId: Long?,
    val updatedAtMs: Long,
)

/** Normalisation de recherche : "Été Brûlant" -> "ete brulant". */
fun searchKey(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()
