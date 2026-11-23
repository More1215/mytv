package dev.mytv.data.source

import dev.mytv.data.source.xtream.XtreamSource
import dev.mytv.domain.model.AccountInfo
import dev.mytv.domain.model.MovieDetails
import dev.mytv.domain.model.SeriesDetails
import dev.mytv.domain.model.PortalCredentials
import dev.mytv.domain.model.PortalType
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Abstraction d'une source IPTV. Les repositories ne parlent qu'à cette interface :
 * ajouter le M3U/XMLTV simple plus tard = écrire un `M3uSource` et l'enregistrer dans
 * [IptvSourceFactory], sans toucher au domain ni à l'UI.
 *
 * Les méthodes lèvent des exceptions techniques ; la traduction en [dev.mytv.domain.result.AppError]
 * est centralisée dans `ErrorMapper`.
 */
interface IptvSource {
    suspend fun authenticate(): AccountInfo

    suspend fun liveCategories(): List<SourceCategory>

    /** Chaînes dans l'ordre du portail. */
    suspend fun liveChannels(): List<SourceChannel>

    /** Programme court d'une chaîne (en cours + suivants), ou liste vide. */
    suspend fun shortEpg(streamId: Long, limit: Int): List<SourceProgram>

    /** Ouvre le guide XMLTV complet en flux. L'appelant DOIT fermer le flux. */
    suspend fun openXmltv(): InputStream

    fun liveStreamUrl(streamId: Long): String

    // ------------------------------------------------------------------ VOD
    suspend fun movieCategories(): List<SourceCategory>
    suspend fun movies(): List<SourceMovie>
    suspend fun movieDetails(streamId: Long): MovieDetails
    suspend fun seriesCategories(): List<SourceCategory>
    suspend fun series(): List<SourceSeries>
    suspend fun seriesDetails(seriesId: Long): SeriesDetails
    fun movieUrl(streamId: Long, extension: String): String
    fun episodeUrl(episodeId: Long, extension: String): String
}

data class SourceMovie(
    val streamId: Long,
    val name: String,
    val posterUrl: String?,
    val rating: Double?,
    val categoryId: String?,
    val extension: String,
    val addedAtEpochSec: Long?,
)

data class SourceSeries(
    val seriesId: Long,
    val name: String,
    val coverUrl: String?,
    val rating: Double?,
    val categoryId: String?,
)

/** Modèles neutres renvoyés par une source, indépendants du format (Xtream, M3U...). */
data class SourceCategory(val id: String, val name: String)

data class SourceChannel(
    val streamId: Long,
    val number: Int?,
    val name: String,
    val logoUrl: String?,
    val categoryId: String?,
    val epgChannelId: String?,
)

data class SourceProgram(val title: String, val description: String?, val startMs: Long, val endMs: Long)

@Singleton
class IptvSourceFactory @Inject constructor(
    private val xtreamFactory: XtreamSource.Factory,
) {
    fun create(type: PortalType, credentials: PortalCredentials, preferredFormat: String? = null): IptvSource = when (type) {
        PortalType.XTREAM -> xtreamFactory.create(credentials, preferredFormat)
    }
}
