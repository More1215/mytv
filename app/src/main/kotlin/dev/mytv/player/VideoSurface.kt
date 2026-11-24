package dev.mytv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.clipToBounds
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
/** Remplissage de l'écran : bandes noires (Adapté), image rognée (Zoom) ou déformée (Étiré). */
enum class VideoScale(val label: String) { FIT("Adapté"), ZOOM("Zoom"), STRETCH("Étiré") }

@Composable
fun VideoSurface(
    player: Player,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
    scale: VideoScale = VideoScale.FIT,
) {
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
    BoxWithConstraints(modifier.background(Color.Black).clipToBounds(), contentAlignment = Alignment.Center) {
        val surfaceModifier = when (scale) {
            VideoScale.FIT -> Modifier.aspectRatio(aspect.floatValue)
            VideoScale.STRETCH -> Modifier.fillMaxSize()
            VideoScale.ZOOM -> {
                // Remplit tout l'écran en gardant les proportions : ce qui dépasse est rogné.
                val a = aspect.floatValue
                if (maxWidth / maxHeight > a) {
                    Modifier.requiredSize(maxWidth, maxWidth / a)
                } else {
                    Modifier.requiredSize(maxHeight * a, maxHeight)
                }
            }
        }
        PlayerSurface(
            player = player,
            surfaceType = if (embedded) SURFACE_TYPE_TEXTURE_VIEW else SURFACE_TYPE_SURFACE_VIEW,
            modifier = surfaceModifier,
        )
    }
}
