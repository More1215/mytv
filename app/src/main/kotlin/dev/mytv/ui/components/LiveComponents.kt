package dev.mytv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import dev.mytv.domain.model.EpgProgram
import dev.mytv.ui.theme.MyTvColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logo de chaîne. On demande à Coil une taille de décodage réduite : un logo 512 px affiché en
 * 40 dp gaspillerait de la RAM sur le Fire TV. Repli sur les initiales si pas de logo.
 */
@Composable
fun ChannelLogo(url: String?, name: String, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(MyTvColors.SurfaceFocused),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.take(2).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MyTvColors.OnSurfaceMuted,
        )
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(url).size(Size(160, 160)).build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size),
            )
        }
    }
}

/** Fine barre de progression du programme en cours. */
@Composable
fun ProgramProgress(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MyTvColors.SurfaceFocused),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .background(MyTvColors.Accent),
        )
    }
}

/** Heure courante, rafraîchie toutes les 30 s (barres de progression, guide). */
@Composable
fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * Heure toujours sur 24 h (« 14:30 »), jamais AM/PM, quelle que soit la langue du Fire TV.
 * SimpleDateFormat n'étant pas thread-safe, une instance par thread.
 */
private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.FRANCE) }
private val dayFormat = ThreadLocal.withInitial { SimpleDateFormat("EEEE d MMMM", Locale.FRANCE) }

fun formatTime(ms: Long): String = timeFormat.get()!!.format(Date(ms))

/** « lundi 23 novembre » */
fun formatDay(ms: Long): String = dayFormat.get()!!.format(Date(ms))

/** Horloge HH:mm, mise à jour pile au changement de minute. */
@Composable
fun Clock(modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000 - value % 60_000)
        }
    }
    Text(formatTime(now), modifier = modifier, style = style)
}

fun EpgProgram.timeRange(): String = "${formatTime(startMs)} – ${formatTime(endMs)}"

@Composable
fun SingleLineText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
) = Text(text, modifier = modifier, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
