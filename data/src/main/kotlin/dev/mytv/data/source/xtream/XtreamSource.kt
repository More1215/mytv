package dev.mytv.data.source.xtream

import android.util.Base64
import dev.mytv.data.di.XmltvClient
import dev.mytv.data.remote.xtream.XtreamApiFactory
import dev.mytv.data.remote.xtream.dto.AuthResponseDto
import dev.mytv.data.remote.xtream.dto.LiveStreamDto
import dev.mytv.data.remote.xtream.dto.toBooleanFlexible
import dev.mytv.data.remote.xtream.dto.toIntOrNullSafe
import dev.mytv.data.remote.xtream.dto.toLongOrNullSafe
import dev.mytv.data.source.IptvSource
import dev.mytv.data.source.SourceCategory
import dev.mytv.data.source.SourceChannel
import dev.mytv.data.source.SourceMovie
import dev.mytv.data.source.SourceProgram
import dev.mytv.data.source.SourceSeries
import dev.mytv.data.remote.xtream.dto.SeriesDto
import dev.mytv.data.remote.xtream.dto.VodStreamDto
import dev.mytv.domain.model.MovieDetails
import dev.mytv.domain.model.SeriesDetails
import dev.mytv.domain.model.AccountInfo
import dev.mytv.domain.model.AccountStatus
import dev.mytv.domain.model.PortalCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import retrofit2.Response
import java.io.InputStream
import javax.inject.Inject

/** Implémentation Xtream Codes de [IptvSource], liée à un jeu d'identifiants. */
class XtreamSource(
    private val credentials: PortalCredentials,
    /** Extension des flux live : "ts" (démarrage le plus rapide) ou "m3u8" (HLS). */
    private val streamFormat: String,
    apiFactory: XtreamApiFactory,
    private val json: Json,
    private val xmltvClient: OkHttpClient,
) : IptvSource {

    private val api = apiFactory.create(credentials.serverUrl)
    private val user get() = credentials.username
    private val pass get() = credentials.password

    override suspend fun authenticate(): AccountInfo {
        val response = api.authenticate(user, pass)
        val userInfo = response.userInfo
            // Réponse JSON valide mais sans user_info : ce n'est pas un portail Xtream.
            ?: throw XtreamProtocolException("user_info manquant")
        if (userInfo.auth.toIntOrNullSafe() != 1) throw XtreamAuthException()
        return response.toAccountInfo(credentials.username)
    }

    override suspend fun liveCategories(): List<SourceCategory> =
        api.liveCategories(user, pass).mapNotNull { dto ->
            val id = dto.categoryId ?: return@mapNotNull null
            SourceCategory(id = id, name = dto.categoryName ?: id)
        }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun liveChannels(): List<SourceChannel> {
        val body = api.liveStreams(user, pass)
        // Décodage en flux sur un thread IO : la liste peut contenir des dizaines de milliers d'entrées.
        return withContext(Dispatchers.IO) {
            body.byteStream().use { stream ->
                json.decodeToSequence<LiveStreamDto>(stream, DecodeSequenceMode.ARRAY_WRAPPED)
                    .mapNotNull { it.toSourceChannel() }
                    .toList()
            }
        }
    }

    override suspend fun shortEpg(streamId: Long, limit: Int): List<SourceProgram> =
        api.shortEpg(user, pass, streamId, limit).listings.mapNotNull { dto ->
            val start = dto.startTimestamp.toLongOrNullSafe() ?: return@mapNotNull null
            val stop = dto.stopTimestamp.toLongOrNullSafe() ?: return@mapNotNull null
            SourceProgram(
                title = dto.title.decodeBase64() ?: return@mapNotNull null,
                description = dto.description.decodeBase64(),
                startMs = start * 1000,
                endMs = stop * 1000,
            )
        }

    override suspend fun openXmltv(): InputStream = withContext(Dispatchers.IO) {
        val url = "${credentials.serverUrl}/xmltv.php".toHttpUrl().newBuilder()
            .addQueryParameter("username", user)
            .addQueryParameter("password", pass)
            .build()
        val response = xmltvClient.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw HttpException(Response.error<Any>(code, okhttp3.ResponseBody.Companion.EMPTY))
        }
        response.body.byteStream()
    }

    override fun liveStreamUrl(streamId: Long): String = mediaUrl("live", streamId, streamFormat)

    // ------------------------------------------------------------------ VOD

    override suspend fun movieCategories(): List<SourceCategory> =
        api.vodCategories(user, pass).toSourceCategories()

    override suspend fun movies(): List<SourceMovie> =
        decodeList<VodStreamDto>(api.vodStreams(user, pass)).mapNotNull(XtreamVodParser::movie)

    override suspend fun movieDetails(streamId: Long): MovieDetails =
        XtreamVodParser.movieDetails(streamId, api.vodInfo(user, pass, streamId))

    override suspend fun seriesCategories(): List<SourceCategory> =
        api.seriesCategories(user, pass).toSourceCategories()

    override suspend fun series(): List<SourceSeries> =
        decodeList<SeriesDto>(api.series(user, pass)).mapNotNull(XtreamVodParser::series)

    override suspend fun seriesDetails(seriesId: Long): SeriesDetails =
        XtreamVodParser.seriesDetails(seriesId, api.seriesInfo(user, pass, seriesId))

    override fun movieUrl(streamId: Long, extension: String): String = mediaUrl("movie", streamId, extension)

    override fun episodeUrl(episodeId: Long, extension: String): String = mediaUrl("series", episodeId, extension)

    /**
     * Format Xtream standard : /{type}/{user}/{pass}/{id}.{ext}. Les identifiants sont encodés
     * pour supporter les caractères spéciaux (@, /, espaces...).
     */
    private fun mediaUrl(type: String, id: Long, extension: String): String =
        credentials.serverUrl.toHttpUrl().newBuilder()
            .addPathSegment(type)
            .addPathSegment(user)
            .addPathSegment(pass)
            .addPathSegment("$id.$extension")
            .build()
            .toString()

    /** Décodage en flux d'un grand tableau JSON, sur un thread IO, sans charger le texte entier. */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend inline fun <reified T> decodeList(body: okhttp3.ResponseBody): List<T> =
        withContext(Dispatchers.IO) {
            body.byteStream().use { json.decodeToSequence<T>(it, DecodeSequenceMode.ARRAY_WRAPPED).toList() }
        }

    private fun List<dev.mytv.data.remote.xtream.dto.LiveCategoryDto>.toSourceCategories() = mapNotNull { dto ->
        val id = dto.categoryId ?: return@mapNotNull null
        SourceCategory(id = id, name = dto.categoryName ?: id)
    }

    class Factory @Inject constructor(
        private val apiFactory: XtreamApiFactory,
        private val json: Json,
        @param:XmltvClient private val xmltvClient: OkHttpClient,
    ) {
        fun create(credentials: PortalCredentials, preferredFormat: String? = null) =
            XtreamSource(credentials, preferredFormat ?: "ts", apiFactory, json, xmltvClient)
    }
}

private fun LiveStreamDto.toSourceChannel(): SourceChannel? {
    val id = streamId.toLongOrNullSafe() ?: return null
    return SourceChannel(
        streamId = id,
        number = num.toIntOrNullSafe(),
        name = name?.trim().orEmpty().ifEmpty { "Chaîne $id" },
        logoUrl = streamIcon?.takeIf { it.startsWith("http", ignoreCase = true) },
        categoryId = categoryId,
        epgChannelId = epgChannelId?.trim()?.takeIf { it.isNotEmpty() },
    )
}

private fun String?.decodeBase64(): String? {
    if (this.isNullOrBlank()) return null
    return try {
        String(Base64.decode(this, Base64.DEFAULT), Charsets.UTF_8).trim().ifEmpty { null }
    } catch (_: IllegalArgumentException) {
        this // certains panels envoient le texte en clair
    }
}

/** Identifiants refusés par le portail (auth = 0). */
class XtreamAuthException : Exception("Identifiants refusés")

/** Le serveur a répondu, mais pas selon le protocole Xtream. */
class XtreamProtocolException(message: String) : Exception(message)

internal fun AuthResponseDto.toAccountInfo(fallbackUsername: String): AccountInfo {
    val user = requireNotNull(userInfo)
    return AccountInfo(
        username = user.username ?: fallbackUsername,
        status = when (user.status?.trim()?.lowercase()) {
            "active" -> AccountStatus.ACTIVE
            "expired" -> AccountStatus.EXPIRED
            "banned" -> AccountStatus.BANNED
            "disabled" -> AccountStatus.DISABLED
            else -> AccountStatus.UNKNOWN
        },
        // exp_date "0" ou absent = abonnement illimité.
        expiresAtEpochSec = user.expDate.toLongOrNullSafe()?.takeIf { it > 0 },
        isTrial = user.isTrial.toBooleanFlexible(),
        activeConnections = user.activeCons.toIntOrNullSafe(),
        maxConnections = user.maxConnections.toIntOrNullSafe(),
        allowedOutputFormats = user.allowedOutputFormats,
        serverTimezone = serverInfo?.timezone,
        httpsPort = serverInfo?.httpsPort.toIntOrNullSafe()?.takeIf { it > 0 },
    )
}
