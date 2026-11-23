package dev.mytv.data.repository

import dev.mytv.data.epg.XmltvParser
import dev.mytv.data.error.runCatchingApp
import dev.mytv.data.local.db.ChannelWithFavorite
import dev.mytv.data.local.db.EpgDao
import dev.mytv.data.local.db.EpgProgramEntity
import dev.mytv.data.local.db.LiveCategoryEntity
import dev.mytv.data.local.db.LiveChannelEntity
import dev.mytv.data.local.db.LiveDao
import dev.mytv.data.local.db.LiveFavoriteEntity
import dev.mytv.data.local.prefs.AppPreferences
import dev.mytv.domain.model.Category
import dev.mytv.domain.model.Channel
import dev.mytv.domain.model.EpgProgram
import dev.mytv.domain.model.LastChannel
import dev.mytv.domain.model.NowNext
import dev.mytv.domain.repository.LiveRepository
import dev.mytv.domain.result.AppResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class LiveRepositoryImpl @Inject constructor(
    private val liveDao: LiveDao,
    private val epgDao: EpgDao,
    private val preferences: AppPreferences,
    private val sources: SourceProvider,
    @param:Named(PortalRepositoryImpl.IO_DISPATCHER) private val io: CoroutineDispatcher,
) : LiveRepository {

    /** Empêche deux synchronisations simultanées (ex. démarrage + bouton Actualiser). */
    private val catalogMutex = Mutex()
    private val epgMutex = Mutex()

    // ------------------------------------------------------------------ Catalogue

    override fun observeCategories(portalId: Long): Flow<List<Category>> =
        liveDao.observeCategories(portalId).map { portalCategories ->
            buildList {
                add(Category(Category.FAVORITES_ID, "Favoris", Category.Kind.FAVORITES))
                add(Category(Category.ALL_ID, "Toutes les chaînes", Category.Kind.ALL))
                portalCategories.mapTo(this) { Category(it.categoryId, it.name) }
            }
        }

    override fun observeChannels(portalId: Long, categoryId: String): Flow<List<Channel>> {
        val source = when (categoryId) {
            Category.FAVORITES_ID -> liveDao.observeFavoriteChannels(portalId)
            Category.ALL_ID -> liveDao.observeAllChannels(portalId)
            else -> liveDao.observeChannelsInCategory(portalId, categoryId)
        }
        return source.map { list -> list.map { it.toDomain() } }.flowOn(io)
    }

    override suspend fun getChannel(portalId: Long, streamId: Long): Channel? =
        withContext(io) { liveDao.getChannel(portalId, streamId)?.toDomain() }

    override suspend fun isCatalogStale(portalId: Long, maxAgeMs: Long): Boolean = withContext(io) {
        val last = preferences.lastCatalogSync(portalId)
        last == null || System.currentTimeMillis() - last > maxAgeMs || liveDao.countChannels(portalId) == 0
    }

    override suspend fun refreshCatalog(portalId: Long): AppResult<Unit> = withContext(io) {
        catalogMutex.withLock {
            runCatchingApp {
                val source = sources.get(portalId)
                val categories = source.liveCategories()
                val channels = source.liveChannels()

                val categoryEntities = categories.mapIndexed { i, c -> LiveCategoryEntity(portalId, c.id, c.name, i) }
                val channelEntities = channels.mapIndexed { i, c ->
                    LiveChannelEntity(
                        portalId = portalId,
                        streamId = c.streamId,
                        // Sans numéro fourni, on numérote dans l'ordre du portail.
                        number = c.number ?: (i + 1),
                        name = c.name,
                        logoUrl = c.logoUrl,
                        categoryId = c.categoryId,
                        epgChannelId = c.epgChannelId,
                        sortOrder = i,
                    )
                }
                liveDao.replaceCatalog(portalId, categoryEntities, channelEntities)
                preferences.setLastCatalogSync(portalId, System.currentTimeMillis())
            }
        }
    }

    override suspend fun toggleFavorite(portalId: Long, streamId: Long) = withContext(io) {
        if (liveDao.isFavorite(portalId, streamId)) {
            liveDao.deleteFavorite(portalId, streamId)
        } else {
            liveDao.insertFavorite(LiveFavoriteEntity(portalId, streamId, System.currentTimeMillis()))
        }
    }

    override suspend fun liveStreamUrl(portalId: Long, streamId: Long): AppResult<String> =
        runCatchingApp { sources.get(portalId).liveStreamUrl(streamId) }

    override suspend fun getLastChannel(portalId: Long): LastChannel? =
        preferences.lastChannel(portalId)?.let { (id, cat) -> LastChannel(id, cat) }

    override suspend fun setLastChannel(portalId: Long, lastChannel: LastChannel) =
        preferences.setLastChannel(portalId, lastChannel.streamId, lastChannel.categoryId)

    // ------------------------------------------------------------------ EPG

    override suspend fun isEpgStale(portalId: Long, maxAgeMs: Long): Boolean {
        val last = preferences.lastEpgSync(portalId)
        return last == null || System.currentTimeMillis() - last > maxAgeMs
    }

    /**
     * Import XMLTV dans une NOUVELLE génération pendant que l'ancienne reste affichée,
     * puis bascule atomique et purge. Ainsi le guide n'est jamais vide ni en double à l'écran,
     * et un import interrompu (réseau coupé) ne casse rien.
     */
    override suspend fun refreshEpg(portalId: Long): AppResult<Unit> = withContext(io) {
        epgMutex.withLock {
            runCatchingApp {
                val now = System.currentTimeMillis()
                val wanted = liveDao.observeAllChannels(portalId).first()
                    .mapNotNullTo(HashSet()) { it.epgChannelId }
                if (wanted.isEmpty()) return@runCatchingApp

                val generation = now
                val parser = XmltvParser(wanted, fromMs = now - EPG_PAST_MS, toMs = now + EPG_FUTURE_MS)
                sources.get(portalId).openXmltv().use { stream ->
                    parser.parse(stream) { batch ->
                        epgDao.insert(batch.map { p ->
                            EpgProgramEntity(portalId, generation, p.channelId, p.startMs, p.endMs, p.title, p.description)
                        })
                    }
                }
                preferences.commitEpgGeneration(portalId, generation, now)
                epgDao.deleteObsolete(portalId, keepGeneration = generation, olderThanMs = now - EPG_PAST_MS)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeNowNext(portalId: Long, epgChannelIds: List<String>): Flow<Map<String, NowNext>> =
        preferences.epgGeneration(portalId).distinctUntilChanged().flatMapLatest { generation ->
            // Requête relancée chaque minute pour suivre l'avancement des programmes.
            flow {
                while (true) {
                    val now = System.currentTimeMillis()
                    val programs = queryPrograms(portalId, generation, epgChannelIds, now, now + NOW_NEXT_HORIZON_MS)
                    emit(programs.mapValues { (_, list) -> list.toNowNext(now) })
                    delay(60_000)
                }
            }
        }.flowOn(io)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeGuide(portalId: Long, epgChannelIds: List<String>, fromMs: Long, toMs: Long): Flow<Map<String, List<EpgProgram>>> =
        preferences.epgGeneration(portalId).distinctUntilChanged().map { generation ->
            queryPrograms(portalId, generation, epgChannelIds, fromMs, toMs)
        }.flowOn(io)

    override suspend fun fetchShortEpg(portalId: Long, streamId: Long): AppResult<NowNext> = withContext(io) {
        runCatchingApp {
            val now = System.currentTimeMillis()
            sources.get(portalId).shortEpg(streamId, limit = 4)
                .map { EpgProgram(it.title, it.description, it.startMs, it.endMs) }
                .toNowNext(now)
        }
    }

    /** SQLite limite le nombre de paramètres d'une requête : on découpe la liste d'ids. */
    private suspend fun queryPrograms(
        portalId: Long, generation: Long, ids: List<String>, fromMs: Long, toMs: Long,
    ): Map<String, List<EpgProgram>> {
        if (ids.isEmpty()) return emptyMap()
        val result = HashMap<String, MutableList<EpgProgram>>()
        ids.distinct().chunked(900).forEach { chunk ->
            epgDao.programs(portalId, generation, chunk, fromMs, toMs).forEach { e ->
                result.getOrPut(e.epgChannelId) { ArrayList() } += EpgProgram(e.title, e.description, e.startMs, e.endMs)
            }
        }
        return result
    }

    private companion object {
        /** Fenêtre conservée : -2 h / +24 h (choix validé). */
        const val EPG_PAST_MS = 2L * 60 * 60 * 1000
        const val EPG_FUTURE_MS = 24L * 60 * 60 * 1000
        const val NOW_NEXT_HORIZON_MS = 12L * 60 * 60 * 1000
    }
}

internal fun List<EpgProgram>.toNowNext(nowMs: Long): NowNext {
    val sorted = sortedBy { it.startMs }
    val currentIndex = sorted.indexOfFirst { it.startMs <= nowMs && it.endMs > nowMs }
    return if (currentIndex >= 0) {
        NowNext(sorted[currentIndex], sorted.getOrNull(currentIndex + 1))
    } else {
        NowNext(null, sorted.firstOrNull { it.startMs > nowMs })
    }
}

private fun ChannelWithFavorite.toDomain() = Channel(
    streamId = streamId,
    number = number,
    name = name,
    logoUrl = logoUrl,
    categoryId = categoryId,
    epgChannelId = epgChannelId,
    isFavorite = isFavorite,
)
