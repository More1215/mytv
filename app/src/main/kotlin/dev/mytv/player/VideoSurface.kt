package dev.mytv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW

/**
 * Surface vidéo qui respecte le format de l'image (16:9, 4:3...) avec bandes noires.
 *
 * - [embedded] = false (plein écran) : SurfaceView, rendu direct par le compositeur matériel,
 *   nettement moins coûteux en CPU/GPU sur le Fire TV Stick.
 * - [embedded] = true (petite fenêtre d'aperçu au milieu d'autres composants) : TextureView.
 *   Une SurfaceView y « perce » la fenêtre et efface tout ce qui est dessiné avant elle à l'écran ;
 *   la TextureView se compose comme une vue normale. Son surcoût est négligeable à cette taille.
 */
@Composable
fun VideoSurface(player: Player, modifier: Modifier = Modifier, embedded: Boolean = false) {
    val aspect = remember { mutableFloatStateOf(16f / 9f) }
    DisposableEffect(player) {
        fun update(size: VideoSize) {
            if (size.width > 0 && size.height > 0) {
                aspect.floatValue = size.width * size.pixelWidthHeightRatio / size.height
            }
        }
        update(player.videoSize)
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) = update(videoSize)
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        PlayerSurface(
            player = player,
            surfaceType = if (embedded) SURFACE_TYPE_TEXTURE_VIEW else SURFACE_TYPE_SURFACE_VIEW,
            modifier = Modifier.aspectRatio(aspect.floatValue),
        )
    }
}
