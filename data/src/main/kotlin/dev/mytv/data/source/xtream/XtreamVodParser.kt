package dev.mytv.data.source.xtream

import dev.mytv.data.remote.xtream.dto.SeriesDto
import dev.mytv.data.remote.xtream.dto.VodStreamDto
import dev.mytv.data.remote.xtream.dto.double
import dev.mytv.data.remote.xtream.dto.long
import dev.mytv.data.remote.xtream.dto.obj
import dev.mytv.data.remote.xtream.dto.objects
import dev.mytv.data.remote.xtream.dto.parseDurationSec
import dev.mytv.data.remote.xtream.dto.str
import dev.mytv.data.remote.xtream.dto.toLongOrNullSafe
import dev.mytv.data.source.SourceMovie
import dev.mytv.data.source.SourceSeries
import dev.mytv.domain.model.Episode
import dev.mytv.domain.model.Movie
import dev.mytv.domain.model.MovieDetails
import dev.mytv.domain.model.Season
import dev.mytv.domain.model.Series
import dev.mytv.domain.model.SeriesDetails
import kotlinx.serialization.json.JsonObject

/** Conversion des réponses VOD Xtream (souvent incohérentes) en modèles propres. */
internal object XtreamVodParser {

    private const val DEFAULT_EXTENSION = "mp4"

    fun movie(dto: VodStreamDto): SourceMovie? {
        val id = dto.streamId.toLongOrNullSafe() ?: return null
        return SourceMovie(
            streamId = id,
            name = dto.name?.trim().orEmpty().ifEmpty { "Film $id" },
            posterUrl = dto.streamIcon.httpOrNull(),
            rating = dto.rating?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 },
            categoryId = dto.categoryId,
            extension = dto.containerExtension.cleanExtension(),
            addedAtEpochSec = dto.added.toLongOrNullSafe(),
        )
    }

    fun series(dto: SeriesDto): SourceSeries? {
        val id = dto.seriesId.toLongOrNullSafe() ?: return null
        return SourceSeries(
            seriesId = id,
            name = dto.name?.trim().orEmpty().ifEmpty { "Série $id" },
            coverUrl = dto.cover.httpOrNull(),
            rating = dto.rating?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 },
            categoryId = dto.categoryId,
        )
    }

    /** Réponse de get_vod_info : { "info": {...}, "movie_data": {...} }. */
    fun movieDetails(streamId: Long, root: JsonObject): MovieDetails {
        val info = root["info"].obj()
        val data = root["movie_data"].obj()
        val movie = Movie(
            streamId = streamId,
            name = data.str("name") ?: info.str("name") ?: "Film $streamId",
            posterUrl = info.str("movie_image").httpOrNull() ?: info.str("cover_big").httpOrNull(),
            rating = info.double("rating")?.takeIf { it > 0 },
            categoryId = data.str("category_id"),
            containerExtension = data.str("container_extension").cleanExtension(),
            addedAtEpochSec = data.long("added"),
        )
        return MovieDetails(
            movie = movie,
            plot = info.str("plot") ?: info.str("description"),
            genre = info.str("genre"),
            director = info.str("director"),
            cast = info.str("cast") ?: info.str("actors"),
            releaseDate = info.str("releasedate") ?: info.str("release_date"),
            durationSec = info.long("duration_secs") ?: parseDurationSec(info.str("duration")),
            backdropUrl = info.str("backdrop_path").httpOrNull(),
        )
    }

    /**
     * Réponse de get_series_info : { "info": {...}, "seasons": [...], "episodes": {...} }.
     * "episodes" est le plus souvent un objet { "1": [ep, ep], "2": [...] }, parfois un tableau.
     */
    fun seriesDetails(seriesId: Long, root: JsonObject): SeriesDetails {
        val info = root["info"].obj()
        val series = Series(
            seriesId = seriesId,
            name = info.str("name") ?: "Série $seriesId",
            coverUrl = info.str("cover").httpOrNull(),
            rating = info.double("rating")?.takeIf { it > 0 },
            categoryId = info.str("category_id"),
        )
        val seasonNames = root["seasons"].objects().mapNotNull { s ->
            val n = s.long("season_number")?.toInt() ?: return@mapNotNull null
            n to (s.str("name") ?: "Saison $n")
        }.toMap()

        val episodes = root["episodes"].objects().mapNotNull { e ->
            val id = e.long("id") ?: return@mapNotNull null
            val epInfo = e["info"].obj()
            Episode(
                id = id,
                seriesId = seriesId,
                season = e.long("season")?.toInt() ?: 1,
                number = e.long("episode_num")?.toInt() ?: 0,
                title = e.str("title") ?: "Épisode",
                plot = epInfo.str("plot"),
                durationSec = epInfo.long("duration_secs") ?: parseDurationSec(epInfo.str("duration")),
                imageUrl = epInfo.str("movie_image").httpOrNull(),
                containerExtension = e.str("container_extension").cleanExtension(),
            )
        }

        val seasons = episodes.groupBy { it.season }.toSortedMap().map { (number, eps) ->
            Season(number, seasonNames[number] ?: "Saison $number", eps.sortedBy { it.number })
        }
        return SeriesDetails(
            series = series,
            plot = info.str("plot"),
            genre = info.str("genre"),
            cast = info.str("cast"),
            releaseDate = info.str("releaseDate") ?: info.str("release_date"),
            backdropUrl = info.str("backdrop_path").httpOrNull(),
            seasons = seasons,
        )
    }

    private fun String?.httpOrNull(): String? = this?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }

    /** "MKV", ".mp4", null -> "mkv", "mp4", "mp4" (seules lettres/chiffres, pour une URL sûre). */
    private fun String?.cleanExtension(): String =
        this?.trim()?.trimStart('.')?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{2,5}")) } ?: DEFAULT_EXTENSION
}
