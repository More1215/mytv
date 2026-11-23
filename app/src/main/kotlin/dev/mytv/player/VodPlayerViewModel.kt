package dev.mytv.player

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.mytv.domain.model.ContentKey
import dev.mytv.domain.model.Episode
import dev.mytv.domain.model.VodItem
import dev.mytv.domain.result.AppError
import dev.mytv.domain.result.AppResult
import dev.mytv.domain.usecase.GetSeriesDetailsUseCase
import dev.mytv.domain.usecase.GetVodStreamUrlUseCase
import dev.mytv.domain.usecase.ObserveActivePortalUseCase
import dev.mytv.domain.usecase.ObserveResumeUseCase
import dev.mytv.domain.usecase.ResumePolicy
import dev.mytv.domain.usecase.SaveProgressUseCase
import dev.mytv.domain.usecase.nextEpisodeAfter
import dev.mytv.domain.usecase.toVodItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VodPlayerState(
    val item: VodItem,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Cible d'une avance/retour rapide en cours (appuis successifs cumulés), null sinon. */
    val seekPreviewMs: Long? = null,
    val error: AppError? = null,
    val playbackFailed: Boolean = false,
    /** Épisode suivant proposé en fin d'épisode, avec le compte à rebours restant. */
    val nextEpisode: Episode? = null,
    val nextCountdownSec: Int = 0,
)

/**
 * Lecteur films / épisodes. Il a son PROPRE ExoPlayer (le lecteur Live est arrêté pendant ce
 * temps) : ce sont deux usages différents (direct sans fin vs fichier avec position et reprise).
 *
 * La position est enregistrée toutes les [SAVE_INTERVAL_MS], à la pause, en quittant l'écran et
 * quand l'appli passe en arrière-plan : une coupure de courant fait perdre au plus 15 s.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@HiltViewModel
class VodPlayerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    playerFactory: PlayerFactory,
    private val observeActivePortal: ObserveActivePortalUseCase,
    private val getStreamUrl: GetVodStreamUrlUseCase,
    private val observeResume: ObserveResumeUseCase,
    private val saveProgress: SaveProgressUseCase,
    private val getSeriesDetails: GetSeriesDetailsUseCase,
) : ViewModel() {

    val player: ExoPlayer = playerFactory.create()

    private val _state = MutableStateFlow(VodPlayerState(item = savedState.toVodItem()))
    val state: StateFlow<VodPlayerState> = _state.asStateFlow()

    private var seekJob: Job? = null
    private var countdownJob: Job? = null
    private var tickerJob: Job? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
                // Pause = moment naturel pour sauvegarder.
                if (!isPlaying) persistPosition()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.update { it.copy(isBuffering = playbackState == Player.STATE_BUFFERING) }
                if (playbackState == Player.STATE_ENDED) onEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.update { it.copy(playbackFailed = true, isBuffering = false) }
            }
        })
        start(_state.value.item, fromStart = savedState.get<String>(ARG_FROM_START) == "true")
        tickerJob = viewModelScope.launch {
            var sinceSave = 0L
            while (isActive) {
                delay(TICK_MS)
                _state.update { it.copy(positionMs = player.currentPosition, durationMs = player.duration.coerceAtLeast(0)) }
                sinceSave += TICK_MS
                if (sinceSave >= SAVE_INTERVAL_MS && player.isPlaying) {
                    sinceSave = 0
                    persistPosition()
                }
            }
        }
    }

    // ------------------------------------------------------------------ Commandes

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    /**
     * Avance/retour rapide. Les appuis rapprochés se cumulent sur une cible affichée en aperçu,
     * et on ne saute réellement qu'une fois les appuis terminés (évite 10 rechargements réseau).
     */
    fun seekBy(deltaMs: Long) {
        val duration = player.duration.takeIf { it > 0 } ?: return
        val base = _state.value.seekPreviewMs ?: player.currentPosition
        val target = (base + deltaMs).coerceIn(0, duration - 1_000)
        _state.update { it.copy(seekPreviewMs = target) }
        seekJob?.cancel()
        seekJob = viewModelScope.launch {
            delay(SEEK_COMMIT_DELAY_MS)
            player.seekTo(target)
            _state.update { it.copy(seekPreviewMs = null, positionMs = target) }
        }
    }

    fun retry() {
        _state.update { it.copy(playbackFailed = false, error = null) }
        start(_state.value.item, fromStart = false)
    }

    fun playNextNow() {
        val next = _state.value.nextEpisode ?: return
        countdownJob?.cancel()
        val item = next.toVodItem(_state.value.item.title, _state.value.item.imageUrl)
        _state.update { VodPlayerState(item = item) }
        start(item, fromStart = true)
    }

    fun cancelNext() {
        countdownJob?.cancel()
        _state.update { it.copy(nextEpisode = null) }
    }

    fun onAppStopped() {
        player.pause()
        persistPosition()
    }

    // ------------------------------------------------------------------ Interne

    private fun start(item: VodItem, fromStart: Boolean) {
        viewModelScope.launch {
            val portalId = awaitPortal()
            val startAt = if (fromStart) 0L else ResumePolicy.startPosition(observeResume.one(portalId, item.key).first())
            when (val url = getStreamUrl(portalId, item)) {
                is AppResult.Success -> {
                    player.setMediaItem(MediaItem.fromUri(Uri.parse(url.value)), startAt)
                    player.prepare()
                    player.play()
                }
                is AppResult.Failure -> _state.update { it.copy(error = url.error, playbackFailed = true) }
            }
        }
    }

    private fun onEnded() {
        persistPosition(ended = true)
        val item = _state.value.item
        val episodeKey = item.key as? ContentKey.EpisodeKey ?: return
        val seriesId = item.seriesId ?: return
        viewModelScope.launch {
            val details = (getSeriesDetails(awaitPortal(), seriesId) as? AppResult.Success)?.value ?: return@launch
            val next = details.nextEpisodeAfter(episodeKey.episodeId) ?: return@launch
            // Même si l'on quitte pendant le compte à rebours, la suite est retenue.
            saveProgress.markNextUp(awaitPortal(), next.toVodItem(item.title, item.imageUrl))
            _state.update { it.copy(nextEpisode = next, nextCountdownSec = NEXT_EPISODE_COUNTDOWN_SEC) }
            countdownJob = launch {
                for (remaining in NEXT_EPISODE_COUNTDOWN_SEC downTo 1) {
                    _state.update { it.copy(nextCountdownSec = remaining) }
                    delay(1_000)
                }
                playNextNow()
            }
        }
    }

    private fun persistPosition(ended: Boolean = false) {
        val duration = player.duration.takeIf { it > 0 } ?: return
        val position = if (ended) duration else player.currentPosition
        val item = _state.value.item
        viewModelScope.launch(NonCancellable) {
            saveProgress(awaitPortal(), item, position, duration)
        }
    }

    private suspend fun awaitPortal(): Long = observeActivePortal().filterNotNull().first().id

    override fun onCleared() {
        // Dernière sauvegarde en quittant l'écran. Le scope du ViewModel est annulé ici :
        // NonCancellable détache la coroutine pour qu'elle aille au bout sans bloquer l'UI.
        val duration = player.duration
        if (duration > 0) {
            val position = player.currentPosition
            val item = _state.value.item
            viewModelScope.launch(NonCancellable) { saveProgress(awaitPortal(), item, position, duration) }
        }
        player.release()
    }

    companion object {
        const val ARG_KEY = "key"
        const val ARG_EXT = "ext"
        const val ARG_TITLE = "title"
        const val ARG_SUBTITLE = "subtitle"
        const val ARG_IMAGE = "image"
        const val ARG_SERIES = "seriesId"
        const val ARG_FROM_START = "fromStart"

        private const val TICK_MS = 500L
        private const val SAVE_INTERVAL_MS = 15_000L
        private const val SEEK_COMMIT_DELAY_MS = 700L
        private const val NEXT_EPISODE_COUNTDOWN_SEC = 10

        /** Reconstitue l'élément à lire depuis les arguments de navigation. */
        private fun SavedStateHandle.toVodItem(): VodItem {
            val key = checkNotNull(get<String>(ARG_KEY))
            val id = key.substringAfter(':').toLong()
            return VodItem(
                key = if (key.startsWith("episode:")) ContentKey.EpisodeKey(id) else ContentKey.MovieKey(id),
                title = get<String>(ARG_TITLE).orEmpty(),
                subtitle = get<String>(ARG_SUBTITLE)?.takeIf { it.isNotEmpty() },
                imageUrl = get<String>(ARG_IMAGE)?.takeIf { it.isNotEmpty() },
                containerExtension = get<String>(ARG_EXT) ?: "mp4",
                seriesId = get<String>(ARG_SERIES)?.toLongOrNull(),
            )
        }
    }
}
