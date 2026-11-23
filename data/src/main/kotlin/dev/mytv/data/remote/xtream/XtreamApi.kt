package dev.mytv.data.remote.xtream

import dev.mytv.data.remote.xtream.dto.AuthResponseDto
import dev.mytv.data.remote.xtream.dto.LiveCategoryDto
import dev.mytv.data.remote.xtream.dto.ShortEpgDto
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * Endpoints `player_api.php` d'un portail Xtream Codes.
 * Une instance est créée par URL de serveur (voir [XtreamApiFactory]) car chaque portail
 * a sa propre base URL.
 */
interface XtreamApi {
    @GET("player_api.php")
    suspend fun authenticate(
        @Query("username") username: String,
        @Query("password") password: String,
    ): AuthResponseDto

    @GET("player_api.php?action=get_live_categories")
    suspend fun liveCategories(
        @Query("username") username: String,
        @Query("password") password: String,
    ): List<LiveCategoryDto>

    /**
     * Liste de TOUTES les chaînes (souvent plusieurs Mo). Lue en flux ([Streaming]) et décodée
     * élément par élément, pour ne jamais charger le JSON entier en mémoire sur 2 Go de RAM.
     */
    @Streaming
    @GET("player_api.php?action=get_live_streams")
    suspend fun liveStreams(
        @Query("username") username: String,
        @Query("password") password: String,
    ): ResponseBody

    @GET("player_api.php?action=get_short_epg")
    suspend fun shortEpg(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("stream_id") streamId: Long,
        @Query("limit") limit: Int,
    ): ShortEpgDto

    // ------------------------------------------------------------------ VOD

    @GET("player_api.php?action=get_vod_categories")
    suspend fun vodCategories(
        @Query("username") username: String,
        @Query("password") password: String,
    ): List<LiveCategoryDto>

    /** Catalogue complet des films (parfois des dizaines de milliers) : lu en flux. */
    @Streaming
    @GET("player_api.php?action=get_vod_streams")
    suspend fun vodStreams(
        @Query("username") username: String,
        @Query("password") password: String,
    ): ResponseBody

    @GET("player_api.php?action=get_vod_info")
    suspend fun vodInfo(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("vod_id") vodId: Long,
    ): JsonObject

    @GET("player_api.php?action=get_series_categories")
    suspend fun seriesCategories(
        @Query("username") username: String,
        @Query("password") password: String,
    ): List<LiveCategoryDto>

    @Streaming
    @GET("player_api.php?action=get_series")
    suspend fun series(
        @Query("username") username: String,
        @Query("password") password: String,
    ): ResponseBody

    @GET("player_api.php?action=get_series_info")
    suspend fun seriesInfo(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("series_id") seriesId: Long,
    ): JsonObject
}
