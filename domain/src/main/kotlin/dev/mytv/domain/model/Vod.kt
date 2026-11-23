package dev.mytv.domain.model

/** Film du catalogue (liste). Les détails complets sont chargés à la demande : [MovieDetails]. */
data class Movie(
    val streamId: Long,
    val name: String,
    val posterUrl: String?,
    /** Note sur 10, si fournie par le portail. */
    val rating: Double?,
    val categoryId: String?,
    /** Extension du fichier (mp4, mkv...) : nécessaire pour construire l'URL de lecture. */
    val containerExtension: String,
    val addedAtEpochSec: Long?,
)

data class MovieDetails(
    val movie: Movie,
    val plot: String?,
    val genre: String?,
    val director: String?,
    val cast: String?,
    val releaseDate: String?,
    val durationSec: Long?,
    val backdropUrl: String?,
)

/**
 * Case d'une grille d'affiches (film ou série), réduite au strict nécessaire pour l'affichage :
 * les grilles sont paginées et ne gardent en mémoire que quelques centaines de cases.
 */
data class PosterEntry(
    val id: Long,
    val title: String,
    val imageUrl: String?,
    /** Avancement de lecture (films commencés), null sinon. */
    val progress: Float?,
)

/** Série du catalogue (liste). */
data class Series(
    val seriesId: Long,
    val name: String,
    val coverUrl: String?,
    val rating: Double?,
    val categoryId: String?,
)

data class SeriesDetails(
    val series: Series,
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val releaseDate: String?,
    val backdropUrl: String?,
    /** Saisons triées par numéro, chacune avec ses épisodes triés. */
    val seasons: List<Season>,
)

data class Season(val number: Int, val name: String, val episodes: List<Episode>)

data class Episode(
    val id: Long,
    val seriesId: Long,
    val season: Int,
    val number: Int,
    val title: String,
    val plot: String?,
    val durationSec: Long?,
    val imageUrl: String?,
    val containerExtension: String,
)

/**
 * Identifiant stable d'un contenu lisible, pour la reprise de lecture.
 * Forme texte : "movie:123" ou "episode:456".
 */
sealed interface ContentKey {
    val storageKey: String

    data class MovieKey(val streamId: Long) : ContentKey {
        override val storageKey get() = "movie:$streamId"
    }

    data class EpisodeKey(val episodeId: Long) : ContentKey {
        override val storageKey get() = "episode:$episodeId"
    }

    companion object {
        /** Inverse de [storageKey] ; null si le texte n'est pas une clé connue. */
        fun fromStorageKey(key: String): ContentKey? {
            val id = key.substringAfter(':').toLongOrNull() ?: return null
            return when (key.substringBefore(':')) {
                "movie" -> MovieKey(id)
                "episode" -> EpisodeKey(id)
                else -> null
            }
        }
    }
}

/** Position de lecture enregistrée (reprise et rangée « Continuer à regarder »). */
data class ResumePoint(
    val key: String,
    val positionMs: Long,
    val durationMs: Long,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    /** Pour un épisode : la série, afin de rouvrir sa fiche. */
    val seriesId: Long?,
    val updatedAtMs: Long,
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** Ce que l'on demande au lecteur VOD de jouer. */
data class VodItem(
    val key: ContentKey,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val containerExtension: String,
    val seriesId: Long? = null,
)

/** Résultats de recherche, films et séries séparés. */
data class SearchResults(val movies: List<Movie>, val series: List<Series>) {
    val isEmpty: Boolean get() = movies.isEmpty() && series.isEmpty()

    companion object {
        val EMPTY = SearchResults(emptyList(), emptyList())
    }
}
