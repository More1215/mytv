package dev.mytv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import dev.mytv.ui.theme.MyTvColors

/**
 * Affiche de film ou de série (format 2:3), avec titre en dessous et barre de progression
 * optionnelle (reprise de lecture). Les images sont décodées à 200x300 px : une affiche HD de
 * 1000 px gaspillerait la RAM du Fire TV pour un affichage de ~130 dp.
 */
@Composable
fun PosterCard(
    title: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    onFocused: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    Column(modifier) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .then(if (onFocused != null) Modifier.onFocusGained(onFocused) else Modifier),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = MyTvColors.Surface, focusedContainerColor = MyTvColors.Surface),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, MyTvColors.Accent))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        ) {
            Box(Modifier.fillMaxSize()) {
                // Titre en repli, visible tant que l'image n'est pas chargée (ou s'il n'y en a pas).
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MyTvColors.OnSurfaceMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                )
                if (imageUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(imageUrl).size(Size(200, 300)).build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (progress != null && progress > 0f) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color(0xAA000000))
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                    ) {
                        ProgramProgress(progress)
                    }
                }
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/** Appelle [action] quand le composant reçoit le focus. */
fun Modifier.onFocusGained(action: () -> Unit): Modifier =
    this.onFocusChanged { if (it.isFocused) action() }
