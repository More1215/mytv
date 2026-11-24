package dev.mytv.domain.usecase

import dev.mytv.domain.model.Category
import dev.mytv.domain.model.Channel
import dev.mytv.domain.model.EpgProgram
import dev.mytv.domain.model.LastChannel
import dev.mytv.domain.model.NowNext
import dev.mytv.domain.repository.LiveRepository
import dev.mytv.domain.result.AppResult
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class SearchChannelsUseCase @Inject constructor(private val repo: LiveRepository) {
    suspend operator fun invoke(portalId: Long, query: String): List<Channel> = repo.searchChannels(portalId, query, limit = 40)
}

/** Chaînes par identifiant, dans l'ordre donné (celles qui n'existent plus sont ignorées). */
class GetChannelsUseCase @Inject constructor(private val repo: LiveRepository) {
    suspend operator fun invoke(portalId: Long, streamIds: List<Long>): List<Channel> =
        streamIds.mapNotNull { repo.getChannel(portalId, it) }
}

class ObserveCategoriesUseCase @Inject constructor(private val repo: LiveRepository) {
    operator fun invoke(portalId: Long): Flow<List<Category>> = repo.observeCategories(portalId)
}

class ObserveChannelsUseCase @Inject constructor(private val repo: LiveRepository) {
    operator fun invoke(portalId: Long, categoryId: String): Flow<List<Channel>> =
        repo.observeChannels(portalId, categoryId)
}

/**
 * Synchronise le catalogue puis le guide, seulement s'ils sont périmés (ou si [force]).
 * Le guide est secondaire : son échec ne fait pas échouer la synchro du catalogue.
 */
class SyncLiveUseCase @Inject constructor(private val repo: LiveRepository) {
    suspend operator fun invoke(portalId: Long, force: Boolean = false): AppResult<Unit> {
        if (force || repo.isCatalogStale(portalId, CATALOG_MAX_AGE_MS)) {
            val result = repo.refreshCatalog(portalId)
            if (result is AppResult.Failure) return result
        }
        if (force || repo.isEpgStale(portalId, EPG_MAX_AGE_MS)) {
            repo.refreshEpg(portalId)
        }
        return AppResult.Success(Unit)
    }

    companion object {
        const val CATALOG_MAX_AGE_MS = 12L * 60 * 60 * 1000
        /** Le guide couvre +24 h : on le rafraîchit toutes les 6 h pour garder de l'avance. */
        const val EPG_MAX_AGE_MS = 6L * 60 * 60 * 1000
    }
}

class ObserveNowNextUseCase @Inject constructor(private val repo: LiveRepository) {
    operator fun invoke(portalId: Long, epgChannelIds: List<String>): Flow<Map<String, NowNext>> =
        repo.observeNowNext(portalId, epgChannelIds)
}

class ObserveGuideUseCase @Inject constructor(private val repo: LiveRepository) {
    operator fun invoke(portalId: Long, epgChannelIds: List<String>, fromMs: Long, toMs: Long): Flow<Map<String, List<EpgProgram>>> =
        repo.observeGuide(portalId, epgChannelIds, fromMs, toMs)
}

class GetShortEpgUseCase @Inject constructor(private val repo: LiveRepository) {
    suspend operator fun invoke(portalId: Long, streamId: Long): AppResult<NowNext> = repo.fetchShortEpg(portalId, streamId)
}

class ToggleFavoriteUseCase @Inject constructor(private val repo: LiveRepository) {
    suspend operator fun invoke(portalId: Long, streamId: Long) = repo.toggleFavorite(portalId, streamId)
}

class GetLiveStreamUrlUseCase @Inject constructor(private val repo: LiveRepository) {
    suspend operator fun invoke(portalId: Long, streamId: Long): AppResult<String> = repo.liveStreamUrl(portalId, streamId)
}

class LastChannelUseCase @Inject constructor(private val repo: LiveRepository) {
    /** Renvoie la dernière chaîne regardée si elle existe encore dans le catalogue. */
    suspend fun get(portalId: Long): Pair<Channel, String>? {
        val last = repo.getLastChannel(portalId) ?: return null
        val channel = repo.getChannel(portalId, last.streamId) ?: return null
        return channel to last.categoryId
    }

    suspend fun set(portalId: Long, channel: Channel, categoryId: String) =
        repo.setLastChannel(portalId, LastChannel(channel.streamId, categoryId))
}

/** Chaînes souvent regardées (« Accès rapide » de l'accueil). */
class ChannelStatsUseCase @Inject constructor(private val repo: LiveRepository) {
    suspend fun recordWatched(portalId: Long, streamId: Long) = repo.recordChannelWatched(portalId, streamId)
    suspend fun forget(portalId: Long, streamId: Long) = repo.forgetChannelWatched(portalId, streamId)
    fun mostWatched(portalId: Long, limit: Int = 2): Flow<List<Channel>> = repo.observeMostWatched(portalId, limit)
}
