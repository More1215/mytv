package dev.mytv.player

import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.mytv.domain.model.Category
import dev.mytv.domain.model.Channel
import dev.mytv.domain.model.NowNext
import dev.mytv.domain.result.AppResult
import dev.mytv.domain.usecase.GetLiveStreamUrlUseCase
import dev.mytv.domain.usecase.GetShortEpgUseCase
import dev.mytv.domain.usecase.LastChannelUseCase
import dev.mytv.domain.usecase.ObserveActivePortalUseCase
import dev.mytv.domain.usecase.ObserveCategoriesUseCase
import dev.mytv.domain.usecase.ObserveChannelsUseCase
import dev.mytv.domain.usecase.ObserveNetworkProtectionUseCase
import dev.mytv.domain.usecase.ObserveNowNextUseCase
import dev.mytv.domain.usecase.ToggleFavoriteUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaybackUiState(
    val portalId: Long? = null,
    /** Chaîne affichée (change immédiatement au zap, avant même le chargement du flux). */
    val channel: Channel? = null,
    val categoryId: String = Category.ALL_ID,
    /** Liste dans laquelle on zappe (la catégorie d'où la chaîne a été lancée). */
    val zapList: List<Channel> = emptyList(),
    val nowNext: NowNext = NowNext.EMPTY,
    val isBuffering: Boolean = false,
    val error: PlaybackError? = null,
)

enum class PlaybackError { STREAM_UNAVAILABLE, NETWORK }

/**
 * Lecture Live TV. Ce ViewModel est attaché à l'ACTIVITÉ (et non à un écran) : un seul ExoPlayer
 * vit pour toute la session, partagé entre l'aperçu de l'écran Live et le plein écran.
 * Passer de l'un à l'autre ne recharge donc pas le flux, ce qui rend la navigation instantanée.
 */
@OptIn(UnstableApi::class)
@HiltViewModel
class PlaybackViewModel @Inject constructor(
    playerFactory: PlayerFactory,
    private val observeActivePortal: ObserveActivePortalUseCase,
    private val observeChannels: ObserveChannelsUseCase,
    private val observeNowNext: ObserveNowNextUseCase,
    private val getShortEpg: GetShortEpgUseCase,
    private val getStreamUrl: GetLiveStreamUrlUseCase,
    private val observeCategories: ObserveCategoriesUseCase,
    private val toggleFavorite: ToggleFavoriteUseCase,
    private val lastChannel: LastChannelUseCase,
    observeProtection: ObserveNetworkProtectionUseCase,
) : ViewModel() {

    val player: ExoPlayer = playerFactory.create()

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var listJob: Job? = null
    private var epgJob: Job? = null
    private var retriedCurrent = false

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.update { it.copy(isBuffering = playbackState == Player.STATE_BUFFERING) }
                if (playbackState == Player.STATE_READY) {
                    retriedCurrent = false
                    _state.update { it.copy(error = null) }
                }
            }

            override fun onPlayerError(error: PlaybackException) = handleError(error)
        })
        viewModelScope.launch {
            val portal = observeActivePortal().filterNotNull().first()
            _state.update { it.copy(portalId = portal.id) }
        }
        // Kill switch : le VPN tombe -> on coupe le flux immédiatement (en plus du blocage réseau).
        // Au retour du VPN, l'appli repart de l'accueil : la reprise du direct se fait en revenant
        // sur un écran Live (voir MyTvNavHost), jamais en arrière-plan.
        viewModelScope.launch {
            observeProtection().map { it.blocked }.distinctUntilChanged().collect { blocked ->
                if (blocked) {
                    loadJob?.cancel()
                    player.stop()
                }
            }
        }
    }

    /** Dernière chaîne regardée (pour la relancer au démarrage), ou null. */
    suspend fun lastWatched(): Pair<Channel, String>? {
        val portalId = awaitPortal()
        return lastChannel.get(portalId)
    }

    /** Lance [channel] et fixe la liste de zapping sur [categoryId]. */
    fun play(channel: Channel, categoryId: String) {
        if (_state.value.channel?.streamId == channel.streamId && player.playbackState != Player.STATE_IDLE) {
            return // déjà en cours : on ne relance pas le flux (retour au plein écran instantané)
        }
        setZapList(categoryId)
        switchTo(channel, debounceMs = 0)
    }

    /** Chaîne précédente/suivante dans la liste courante (boucle aux extrémités). */
    fun zap(delta: Int) {
        val s = _state.value
        val list = s.zapList.ifEmpty { return }
        val index = list.indexOfFirst { it.streamId == s.channel?.streamId }.coerceAtLeast(0)
        val target = list[Math.floorMod(index + delta, list.size)]
        // Debounce : en appuyant plusieurs fois vite, seul le flux de la chaîne finale est chargé.
        switchTo(target, debounceMs = ZAP_DEBOUNCE_MS)
    }

    // ---------------------------------------------------------------- Sélecteur de chaînes

    /** Catégories proposées dans le sélecteur du lecteur (Gauche / OK). */
    @kotlin.OptIn(ExperimentalCoroutinesApi::class)
    val categories: StateFlow<List<Category>> = _state.map { it.portalId }.filterNotNull().distinctUntilChanged()
        .flatMapLatest { observeCategories(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val browseCategoryId = MutableStateFlow<String?>(null)
    val browsedCategoryId: StateFlow<String?> = browseCategoryId.asStateFlow()

    /** Chaînes de la catégorie parcourue dans le sélecteur (null = chargement). */
    @kotlin.OptIn(ExperimentalCoroutinesApi::class)
    val browsedChannels: StateFlow<List<Channel>?> = browseCategoryId
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else flow<List<Channel>?> { emitAll(observeChannels(awaitPortal(), id)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Ouverture du sélecteur : il part de la catégorie de la chaîne en cours. */
    fun openBrowser() {
        browseCategoryId.value = _state.value.categoryId
    }

    fun browseCategory(categoryId: String) {
        browseCategoryId.value = categoryId
    }
    // ---------------------------------------------------------------- Divers

    fun toggleCurrentFavorite() {
        val channel = _state.value.channel ?: return
        viewModelScope.launch {
            toggleFavorite(awaitPortal(), channel.streamId)
            _state.update { s -> s.copy(channel = s.channel?.copy(isFavorite = !channel.isFavorite)) }
        }
    }

    fun retry() {
        val channel = _state.value.channel ?: return
        player.stop()
        switchTo(channel, debounceMs = 0)
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Appli en arrière-plan (bouton Home) : on coupe le flux pour libérer réseau et décodeur. */
    fun onAppStopped() {
        player.stop()
    }

    /** Retour au premier plan : on reprend le direct (et non la position mise en pause). */
    fun onAppStarted() {
        if (_state.value.channel != null && player.playbackState == Player.STATE_IDLE) {
            player.seekToDefaultPosition()
            player.prepare()
        }
    }

    // ---------------------------------------------------------------- Interne

    private fun setZapList(categoryId: String) {
        if (categoryId == _state.value.categoryId && listJob != null) return
        _state.update { it.copy(categoryId = categoryId) }
        listJob?.cancel()
        listJob = viewModelScope.launch {
            observeChannels(awaitPortal(), categoryId).collect { list ->
                _state.update { it.copy(zapList = list) }
            }
        }
    }

    private fun switchTo(channel: Channel, debounceMs: Long) {
        _state.update { it.copy(channel = channel, error = null, nowNext = NowNext.EMPTY) }
        observeEpgFor(channel)
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val portalId = awaitPortal()
            when (val url = getStreamUrl(portalId, channel.streamId)) {
                is AppResult.Success -> {
                    retriedCurrent = false
                    player.setMediaItem(mediaItemFor(url.value))
                    player.prepare()
                    player.play()
                    lastChannel.set(portalId, channel, _state.value.categoryId)
                }
                is AppResult.Failure -> _state.update { it.copy(error = PlaybackError.NETWORK) }
            }
        }
    }

    private fun observeEpgFor(channel: Channel) {
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            val portalId = awaitPortal()
            val epgId = channel.epgChannelId
            if (epgId != null) {
                observeNowNext(portalId, listOf(epgId)).collect { map ->
                    val nowNext = map[epgId]
                    if (nowNext != null && nowNext.now != null) {
                        _state.update { it.copy(nowNext = nowNext) }
                    } else {
                        loadShortEpg(portalId, channel)
                    }
                }
            } else {
                loadShortEpg(portalId, channel)
            }
        }
    }

    /** Repli get_short_epg quand le guide XMLTV n'a rien pour cette chaîne. */
    private suspend fun loadShortEpg(portalId: Long, channel: Channel) {
        val result = getShortEpg(portalId, channel.streamId)
        if (result is AppResult.Success && _state.value.channel?.streamId == channel.streamId) {
            _state.update { it.copy(nowNext = result.value) }
        }
    }

    private fun handleError(error: PlaybackException) {
        // Fenêtre du direct dépassée (pause trop longue, réseau lent) : on recale sur le direct.
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        // Une relance automatique : les flux IPTV coupent souvent une fois puis repartent.
        if (!retriedCurrent) {
            retriedCurrent = true
            viewModelScope.launch {
                delay(1_000)
                player.prepare()
            }
            return
        }
        val kind = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> PlaybackError.NETWORK
            else -> PlaybackError.STREAM_UNAVAILABLE
        }
        _state.update { it.copy(error = kind, isBuffering = false) }
    }

    private fun mediaItemFor(url: String): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        // L'extension suffit d'ordinaire, mais on force HLS pour les URLs .m3u8 avec paramètres.
        if (url.contains(".m3u8")) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        return builder
            // Réglage du direct : rester proche du bord du direct sans multiplier les rebuffers.
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(5_000).build())
            .build()
    }

    private suspend fun awaitPortal(): Long =
        _state.value.portalId ?: observeActivePortal().filterNotNull().first().id

    override fun onCleared() {
        player.release()
    }

    private companion object {
        const val ZAP_DEBOUNCE_MS = 350L
    }
}
