package dev.mytv.ui.vod

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.mytv.player.VideoScale
import dev.mytv.player.audioChoices
import dev.mytv.player.select
import dev.mytv.player.selectedIndex
import dev.mytv.player.textChoices
import dev.mytv.ui.common.tryRequestFocus
import dev.mytv.ui.components.SingleLineText
import dev.mytv.ui.theme.MyTvColors

/** Taille des sous-titres choisie dans le panneau. */
enum class SubtitleSize(val label: String, val sp: Int) { NORMAL("Normale", 26), LARGE("Grande", 32), XLARGE("Très grande", 40) }

private val SPEEDS = listOf(1f, 1.25f, 1.5f, 0.75f)

/**
 * Panneau « Bas » du lecteur : audio, sous-titres, taille, format d'image, vitesse.
 * OK fait défiler les choix de chaque ligne (même logique que les Réglages).
 */
@Composable
fun PlayerSettingsPanel(
    player: Player,
    scale: VideoScale,
    onScale: (VideoScale) -> Unit,
    subtitleSize: SubtitleSize,
    onSubtitleSize: (SubtitleSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Les pistes ne sont connues qu'une fois le média chargé : on se recompose à chaque changement.
    var tracksVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) { tracksVersion++ }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    val audio = remember(tracksVersion) { player.audioChoices() }
    val text = remember(tracksVersion) { player.textChoices() }
    val audioIndex = remember(tracksVersion) { player.selectedIndex(audio) }
    val textIndex = remember(tracksVersion) {
        if (player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) 0 else player.selectedIndex(text)
    }
    var speed by remember { mutableStateOf(player.playbackParameters.speed) }

    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        first.tryRequestFocus()
    }

    Column(
        modifier
            .fillMaxHeight()
            .width(460.dp)
            .background(Color(0xEE0B0F14))
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Text("Réglages de lecture", style = MaterialTheme.typography.headlineSmall)
        Text("OK fait défiler les choix · Retour : fermer", style = MaterialTheme.typography.labelMedium, color = MyTvColors.OnSurfaceMuted)
        PanelRow(
            title = "Audio",
            value = audio.getOrNull(audioIndex)?.label ?: "Piste unique",
            enabled = audio.size > 1,
            onClick = { player.select(audio[(audioIndex + 1) % audio.size], C.TRACK_TYPE_AUDIO) },
            modifier = Modifier.focusRequester(first),
        )
        PanelRow(
            title = "Sous-titres",
            value = text.getOrNull(textIndex)?.label ?: "Désactivés",
            enabled = text.size > 1,
            onClick = { player.select(text[(textIndex + 1) % text.size], C.TRACK_TYPE_TEXT) },
        )
        PanelRow(
            title = "Taille des sous-titres",
            value = subtitleSize.label,
            onClick = { onSubtitleSize(SubtitleSize.entries[(subtitleSize.ordinal + 1) % SubtitleSize.entries.size]) },
        )
        PanelRow(
            title = "Format d'image",
            value = scale.label,
            onClick = { onScale(VideoScale.entries[(scale.ordinal + 1) % VideoScale.entries.size]) },
        )
        PanelRow(
            title = "Vitesse",
            value = formatSpeed(speed),
            onClick = {
                val next = SPEEDS[(SPEEDS.indexOf(speed).coerceAtLeast(0) + 1) % SPEEDS.size]
                player.setPlaybackSpeed(next)
                speed = next
            },
        )
    }
}

@Composable
private fun PanelRow(title: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MyTvColors.Surface,
            contentColor = MyTvColors.OnBackground,
            focusedContainerColor = MyTvColors.Accent,
            focusedContentColor = Color.White,
            disabledContainerColor = MyTvColors.Surface.copy(alpha = 0.5f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            SingleLineText(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun formatSpeed(s: Float): String = if (s == 1f) "Normale" else "×" + s.toString().removeSuffix(".0").replace('.', ',')

/**
 * Sous-titres du lecteur (la surface vidéo Compose ne les dessine pas). Seul le texte affiché à
 * l'instant est gardé en mémoire ; les sous-titres en image (DVB/PGS) sont aussi gérés.
 */
@Composable
fun Subtitles(player: Player, size: SubtitleSize, modifier: Modifier = Modifier) {
    var cues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) { cues = cueGroup.cues }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    if (cues.isEmpty()) return
    Column(modifier.padding(bottom = 56.dp, start = 80.dp, end = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        cues.forEach { cue ->
            val bitmap = cue.bitmap
            val text = cue.text
            when {
                bitmap != null -> Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth(0.8f))
                !text.isNullOrBlank() -> Text(
                    text.toString(),
                    style = TextStyle(fontSize = size.sp.sp, color = Color.White, textAlign = TextAlign.Center),
                    modifier = Modifier
                        .background(Color(0xB3000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}
