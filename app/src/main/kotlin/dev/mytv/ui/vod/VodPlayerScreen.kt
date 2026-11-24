package dev.mytv.ui.vod

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.mytv.player.VideoScale
import dev.mytv.player.VideoSurface
import dev.mytv.player.VodPlayerViewModel
import dev.mytv.ui.common.toMessage
import dev.mytv.ui.common.tryRequestFocus
import dev.mytv.ui.components.Clock
import dev.mytv.ui.components.ProgramProgress
import dev.mytv.ui.components.SingleLineText
import dev.mytv.ui.theme.MyTvColors
import kotlinx.coroutines.delay

/**
 * Lecteur films / épisodes.
 *
 * Télécommande : OK ou ⏯ = lecture/pause · ◀ = -10 s · ▶ = +30 s (appuis cumulables)
 * · ⏪ ⏩ = idem · Haut/Bas = afficher les commandes · Retour = quitter (position enregistrée).
 */
@Composable
fun VodPlayerScreen(onBack: () -> Unit, viewModel: VodPlayerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rootFocus = remember { FocusRequester() }
    var controlsTrigger by remember { mutableIntStateOf(1) }
    var controlsVisible by remember { mutableStateOf(true) }
    // Panneau « Bas » : audio, sous-titres, format d'image, vitesse.
    var settingsOpen by remember { mutableStateOf(false) }
    var scale by remember { mutableStateOf(VideoScale.FIT) }
    var subtitleSize by remember { mutableStateOf(SubtitleSize.NORMAL) }

    LaunchedEffect(controlsTrigger, state.isPlaying) {
        controlsVisible = true
        // En pause, les commandes restent affichées ; en lecture, elles s'effacent après 4 s.
        if (state.isPlaying) {
            delay(4_000)
            controlsVisible = false
        }
    }
    // Au démarrage et à la fermeture du panneau : la racine reprend les touches.
    LaunchedEffect(settingsOpen) {
        if (settingsOpen) return@LaunchedEffect
        withFrameNanos { }
        rootFocus.tryRequestFocus()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onAppStopped() }
    fun back() {
        when {
            settingsOpen -> settingsOpen = false
            state.nextEpisode != null -> viewModel.cancelNext()
            else -> onBack()
        }
    }
    BackHandler { back() }

    val overlayOpen = state.nextEpisode != null || state.playbackFailed

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Retour intercepté avant les enfants : sinon, quand une ligne du panneau a le focus,
            // Compose consomme le premier Retour pour « sortir » le focus et le panneau reste ouvert.
            .onPreviewKeyEvent { e ->
                if (e.key != Key.Back) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyUp) back()
                true
            }
            .focusRequester(rootFocus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || overlayOpen || settingsOpen) return@onKeyEvent false
                controlsTrigger++
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.Spacebar -> { viewModel.togglePlayPause(); true }
                    Key.MediaPlay -> { if (!state.isPlaying) viewModel.togglePlayPause(); true }
                    Key.MediaPause -> { if (state.isPlaying) viewModel.togglePlayPause(); true }
                    Key.DirectionLeft, Key.MediaRewind -> { viewModel.seekBy(-10_000); true }
                    Key.DirectionRight, Key.MediaFastForward -> { viewModel.seekBy(30_000); true }
                    Key.DirectionDown, Key.Menu -> { settingsOpen = true; true }
                    Key.DirectionUp -> true
                    else -> false
                }
            },
    ) {
        VideoSurface(viewModel.player, Modifier.fillMaxSize(), scale = scale)
        Subtitles(viewModel.player, subtitleSize, Modifier.align(Alignment.BottomCenter))

        if (state.isBuffering && !state.playbackFailed) {
            Text("Chargement…", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center))
        }

        AnimatedVisibility(
            visible = (controlsVisible || state.seekPreviewMs != null) && !overlayOpen && !settingsOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Controls(
                title = state.item.title,
                subtitle = state.item.subtitle,
                isPlaying = state.isPlaying,
                positionMs = state.seekPreviewMs ?: state.positionMs,
                durationMs = state.durationMs,
                seeking = state.seekPreviewMs != null,
            )
        }

        if (settingsOpen && !overlayOpen) {
            PlayerSettingsPanel(
                player = viewModel.player,
                scale = scale,
                onScale = { scale = it },
                subtitleSize = subtitleSize,
                onSubtitleSize = { subtitleSize = it },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        if (state.playbackFailed) {
            Box(Modifier.fillMaxSize().background(Color.Black))
            ActionPanel(
                message = state.error?.toMessage() ?: "Lecture impossible pour le moment.",
                primary = "Réessayer" to viewModel::retry,
                secondary = "Retour" to onBack,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        state.nextEpisode?.let { next ->
            ActionPanel(
                message = "Épisode suivant dans ${state.nextCountdownSec} s\nS${next.season}E${next.number} · ${next.title}",
                primary = "Lire maintenant" to viewModel::playNextNow,
                secondary = "Annuler" to viewModel::cancelNext,
                modifier = Modifier.align(Alignment.BottomEnd).padding(48.dp),
            )
        }
    }
}

@Composable
private fun Controls(
    title: String,
    subtitle: String?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    seeking: Boolean,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                .padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 40.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                SingleLineText(title, style = MaterialTheme.typography.headlineSmall)
                subtitle?.let { SingleLineText(it, style = MaterialTheme.typography.bodyLarge, color = MyTvColors.OnSurfaceMuted) }
            }
            Clock(style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.weight(1f))
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                .padding(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (isPlaying) "▶" else "⏸", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.padding(horizontal = 8.dp))
                Text(
                    "${formatPosition(positionMs)} / ${if (durationMs > 0) formatPosition(durationMs) else "--:--"}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (seeking) MyTvColors.Accent else Color.Unspecified,
                )
            }
            ProgramProgress(if (durationMs > 0) positionMs.toFloat() / durationMs else 0f)
            Text(
                "OK : pause / lecture   ·   ◀ -10 s   ·   ▶ +30 s   ·   ▼ audio, sous-titres   ·   Retour : quitter",
                style = MaterialTheme.typography.labelMedium,
                color = MyTvColors.OnSurfaceMuted,
            )
        }
    }
}

@Composable
private fun ActionPanel(
    message: String,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(message.substringBefore('\n')) {
        withFrameNanos { }
        focus.tryRequestFocus()
    }
    Column(
        modifier
            .background(Color(0xEE151B23), RoundedCornerShape(12.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(message, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = primary.second, modifier = Modifier.focusRequester(focus)) { Text(primary.first) }
            OutlinedButton(onClick = secondary.second) { Text(secondary.first) }
        }
        Spacer(Modifier.height(2.dp))
    }
}
