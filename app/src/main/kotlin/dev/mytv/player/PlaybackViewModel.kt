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
import dev.mytv.domain.usecase.ObservePlayerSettingsUseCase
import dev.mytv.domain.model.PlayerSettings
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
    private val playerFactory: PlayerFactory,
    private val observeSettings: ObservePlayerSettingsUseCase,
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

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update { it.copy(isBuffering = playbackState == Player.STATE_BUFFERING) }
            if (playbackState == Player.STATE_READY) {
                retriedCurrent = false
                _state.update { it.copy(error = null) }
            }
        }

        override fun onPlayerError(error: PlaybackException) = handleError(error)
    }

    /** Réglages avec lesquels le lecteur actuel a été construit. */
    private var builtWith: PlayerSettings = observeSettings().value

    /**
     * Le lecteur peut être RECRÉÉ quand les réglages tampon/décodage changent : les écrans
     * observent donc ce flux (et non une référence figée) pour afficher la bonne surface vidéo.
     */
    private val _player = MutableStateFlow(playerFactory.create(builtWith).also { it.addListener(listener) })
    val playerFlow: StateFlow<ExoPlayer> = _player.asStateFlow()
    val player: ExoPlayer get() = _player.value

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var listJob: Job? = null
    private var epgJob: Job? = null
    private var retriedCurrent = false

    init {
        // Multi-portails : changer de portail actif remet le lecteur à zéro (autres chaînes).
        viewModelScope.launch {
            observeActivePortal().map { it?.id }.distinctUntilChanged().collect { id ->
                val previous = _state.value.portalId
                if (previous != null && previous != id) {
                    loadJob?.cancel(); listJob?.cancel(); epgJob?.cancel()
                    listJob = null
                    player.stop()
                    player.clearMediaItems()
                    _state.value = PlaybackUiState(portalId = id)
                } else {
                    _state.update { it.copy(portalId = id) }
                }
            }
        }
        viewModelScope.launch {
            observeSettings().collect { settings -> applySettings(settings) }
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

    /** Dernière chaîne regardée (pour la relancer au démarrage), ou null si désactivé dans les réglages. */
    suspend fun lastWatched(): Pair<Channel, String>? {
        if (!observeSettings().value.resumeLastChannel) return null
        val portalId = awaitPortal()
        return lastChannel.get(portalId)
    }

    /**
     * Application à chaud des réglages :
     * - tampon / décodage -> nouveau lecteur (ces paramètres ne se changent qu'à la construction),
     *   puis reprise de la chaîne en cours ;
     * - format TS/HLS -> la chaîne en cours est relancée avec la nouvelle URL.
     */
    private fun applySettings(settings: PlayerSettings) {
        val old = builtWith
        builtWith = settings
        val channel = _state.value.channel
        when {
            settings.buffer != old.buffer || settings.decoder != old.decoder -> {
                val wasActive = player.playbackState != Player.STATE_IDLE
                loadJob?.cancel()
                player.removeListener(listener)
                player.release()
                _player.value = playerFactory.create(settings).also { it.addListener(listener) }
                if (channel != null && wasActive) switchTo(channel, debounceMs = 0)
            }
            settings.liveFormat != old.liveFormat && channel != null ->
                if (player.playbackState != Player.STATE_IDLE) {
                    switchTo(channel, debounceMs = 0)
                } else {
                    // Lecture coupée (réglage changé hors de l'écran Live) : on oublie l'ancienne URL,
                    // onAppStarted() relancera la chaîne dans le nouveau format au retour.
                    player.clearMediaItems()
                }
        }
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
        val channel = _state.value.channel ?: return
        if (player.playbackState != Player.STATE_IDLE) return
        if (player.mediaItemCount == 0) {
            // Lecteur recréé entre-temps (réglage tampon/décodage changé hors de l'écran Live) :
            // il n'a pas encore de flux, on relance la chaîne en cours.
            switchTo(channel, debounceMs = 0)
        } else {
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
