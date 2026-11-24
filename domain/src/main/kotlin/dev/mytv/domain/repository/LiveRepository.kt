package dev.mytv.domain.repository

import dev.mytv.domain.model.Category
import dev.mytv.domain.model.Channel
import dev.mytv.domain.model.EpgProgram
import dev.mytv.domain.model.LastChannel
import dev.mytv.domain.model.NowNext
import dev.mytv.domain.result.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Live TV. Toutes les lectures passent par le cache local (Room) : l'UI s'affiche
 * instantanément, même hors ligne, puis se met à jour quand une synchronisation se termine.
 */
interface LiveRepository {
    /** Catégories du portail, précédées de « Favoris » et « Toutes les chaînes ». */
    fun observeCategories(portalId: Long): Flow<List<Category>>

    fun observeChannels(portalId: Long, categoryId: String): Flow<List<Channel>>

    suspend fun getChannel(portalId: Long, streamId: Long): Channel?

    /** Chaînes dont le nom contient [query] (ou dont le numéro vaut [query]). */
    suspend fun searchChannels(portalId: Long, query: String, limit: Int): List<Channel>

    /** true si aucun catalogue n'est en cache ou s'il est plus vieux que [maxAgeMs]. */
    suspend fun isCatalogStale(portalId: Long, maxAgeMs: Long): Boolean

    /** Télécharge catégories + chaînes et remplace le cache. Les favoris sont conservés. */
    suspend fun refreshCatalog(portalId: Long): AppResult<Unit>

    suspend fun isEpgStale(portalId: Long, maxAgeMs: Long): Boolean

    /** Télécharge le guide XMLTV complet et ne garde que la fenêtre utile. */
    suspend fun refreshEpg(portalId: Long): AppResult<Unit>

    /** Programme en cours et suivant, par epgChannelId. Se met à jour au fil du temps. */
    fun observeNowNext(portalId: Long, epgChannelIds: List<String>): Flow<Map<String, NowNext>>

    /** Programmes sur une plage horaire, par epgChannelId (grille du guide). */
    fun observeGuide(portalId: Long, epgChannelIds: List<String>, fromMs: Long, toMs: Long): Flow<Map<String, List<EpgProgram>>>

    /** Repli « now & next » direct sur le portail (get_short_epg) quand le XMLTV n'a rien. */
    suspend fun fetchShortEpg(portalId: Long, streamId: Long): AppResult<NowNext>

    suspend fun toggleFavorite(portalId: Long, streamId: Long)

    /** URL de lecture du flux (contient les identifiants : ne jamais la logger). */
    suspend fun liveStreamUrl(portalId: Long, streamId: Long): AppResult<String>

    suspend fun getLastChannel(portalId: Long): LastChannel?

    suspend fun setLastChannel(portalId: Long, lastChannel: LastChannel)

    /** Compte une chaîne réellement regardée (appelé après ~20 s de lecture). */
    suspend fun recordChannelWatched(portalId: Long, streamId: Long)

    /** Retire une chaîne de l'« Accès rapide » (compteur remis à zéro). */
    suspend fun forgetChannelWatched(portalId: Long, streamId: Long)

    /** Chaînes les plus regardées, pour l'accueil. */
    fun observeMostWatched(portalId: Long, limit: Int): Flow<List<Channel>>
}
