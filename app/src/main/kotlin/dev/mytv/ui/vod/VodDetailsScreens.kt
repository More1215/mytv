package dev.mytv.ui.vod

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import dev.mytv.domain.model.ContentKey
import dev.mytv.domain.model.Episode
import dev.mytv.domain.model.ResumePoint
import dev.mytv.domain.model.VodItem
import dev.mytv.domain.usecase.ResumePolicy
import dev.mytv.domain.usecase.toVodItem
import dev.mytv.ui.common.toMessage
import dev.mytv.ui.common.tryRequestFocus
import dev.mytv.ui.components.ProgramProgress
import dev.mytv.ui.components.SingleLineText
import dev.mytv.ui.theme.MyTvColors

// ======================================================================= Film

@Composable
fun MovieDetailsScreen(
    onPlay: (item: VodItem, fromStart: Boolean) -> Unit,
    viewModel: MovieDetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resume by viewModel.resume.collectAsStateWithLifecycle()
    val playFocus = remember { FocusRequester() }

    when (val s = state) {
        DetailsState.Loading -> CenteredText("Chargement de la fiche…")
        is DetailsState.Failed -> FailedDetails(s.error.toMessage(), viewModel::load)
        is DetailsState.Ready -> {
            val details = s.value
            val movie = details.movie
            val resumeAt = ResumePolicy.startPosition(resume)
            LaunchedEffect(Unit) {
                withFrameNanos { }
                playFocus.tryRequestFocus()
            }
            Row(
                Modifier
                    .fillMaxSize()
                    .background(MyTvColors.Background)
                    .padding(horizontal = 48.dp, vertical = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
            ) {
                Poster(movie.posterUrl, movie.name, Modifier.width(260.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(movie.name, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            details.releaseDate?.take(4),
                            details.genre,
                            details.durationSec?.let(::formatDuration),
                            movie.rating?.let { "★ %.1f".format(it) },
                        ).joinToString("   ·   "),
                        style = MaterialTheme.typography.labelLarge,
                        color = MyTvColors.OnSurfaceMuted,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Button(
                            onClick = { onPlay(movie.toVodItem(), false) },
                            modifier = Modifier.focusRequester(playFocus),
                        ) {
                            Text(if (resumeAt > 0) "▶  Reprendre à ${formatPosition(resumeAt)}" else "▶  Lecture")
                        }
                        if (resumeAt > 0) {
                            OutlinedButton(onClick = { onPlay(movie.toVodItem(), true) }) { Text("Depuis le début") }
                        }
                    }
                    if (resumeAt > 0) ProgramProgress(resume!!.progress, Modifier.width(420.dp))
                    Spacer(Modifier.height(6.dp))
                    details.plot?.let { Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 7, overflow = TextOverflow.Ellipsis) }
                    details.director?.let { InfoLine("Réalisation", it) }
                    details.cast?.let { InfoLine("Avec", it) }
                }
            }
        }
    }
}

// ======================================================================= Série

@Composable
fun SeriesDetailsScreen(
    onPlay: (item: VodItem, fromStart: Boolean) -> Unit,
    viewModel: SeriesDetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectedSeason by viewModel.selectedSeason.collectAsStateWithLifecycle()
    val resumes by viewModel.resumes.collectAsStateWithLifecycle()
    val playFocus = remember { FocusRequester() }

    when (val s = state) {
        DetailsState.Loading -> CenteredText("Chargement de la série…")
        is DetailsState.Failed -> FailedDetails(s.error.toMessage(), viewModel::load)
        is DetailsState.Ready -> {
            val details = s.value
            val series = details.series
            val main = viewModel.mainEpisode(details, resumes)
            val episodes = details.seasons.firstOrNull { it.number == selectedSeason }?.episodes.orEmpty()
            var initialFocusDone by remember { mutableStateOf(false) }
            LaunchedEffect(main?.first?.season) { main?.first?.season?.let(viewModel::suggestSeason) }
            LaunchedEffect(main != null) {
                if (initialFocusDone || main == null) return@LaunchedEffect
                withFrameNanos { }
                initialFocusDone = playFocus.tryRequestFocus()
            }

            fun play(episode: Episode, fromStart: Boolean = false) =
                onPlay(episode.toVodItem(series.name, series.coverUrl), fromStart)

            Row(
                Modifier
                    .fillMaxSize()
                    .background(MyTvColors.Background)
                    .padding(horizontal = 40.dp, vertical = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                // ------------------------------------------------ Infos série
                Column(Modifier.width(320.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Poster(series.coverUrl, series.name, Modifier.width(120.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(series.name, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(details.releaseDate?.take(4), details.genre, "${details.seasons.size} saison${if (details.seasons.size > 1) "s" else ""}")
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium,
                                color = MyTvColors.OnSurfaceMuted,
                            )
                        }
                    }
                    main?.let { (episode, point) ->
                        val resumeAt = ResumePolicy.startPosition(point)
                        val label = "S${episode.season}E${episode.number}"
                        Button(onClick = { play(episode) }, modifier = Modifier.focusRequester(playFocus)) {
                            Text(if (resumeAt > 0) "▶  Reprendre $label" else "▶  Lecture $label")
                        }
                    }
                    details.plot?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis) }
                    details.cast?.let { InfoLine("Avec", it) }
                }

                // ------------------------------------------------ Saisons + épisodes
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (details.seasons.isEmpty()) {
                        Text("Aucun épisode disponible.", color = MyTvColors.OnSurfaceMuted)
                        return@Column
                    }
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(4.dp),
                        modifier = Modifier.focusRestorer(),
                    ) {
                        items(details.seasons, key = { it.number }) { season ->
                            SeasonChip(
                                label = season.name,
                                selected = season.number == selectedSeason,
                                // La saison s'affiche dès qu'elle reçoit le focus (pas besoin d'OK).
                                onFocused = { viewModel.selectSeason(season.number) },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(4.dp),
                        modifier = Modifier.fillMaxSize().focusRestorer(),
                    ) {
                        items(episodes, key = { it.id }) { episode ->
                            EpisodeRow(
                                episode = episode,
                                resume = resumes[ContentKey.EpisodeKey(episode.id).storageKey],
                                onClick = { play(episode) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeasonChip(label: String, selected: Boolean, onFocused: () -> Unit) {
    Surface(
        onClick = onFocused,
        modifier = Modifier.onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) MyTvColors.SurfaceFocused else MyTvColors.Surface,
            contentColor = if (selected) MyTvColors.OnBackground else MyTvColors.OnSurfaceMuted,
            focusedContainerColor = MyTvColors.Accent,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp))
    }
}

@Composable
private fun EpisodeRow(episode: Episode, resume: ResumePoint?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MyTvColors.Surface,
            contentColor = MyTvColors.OnBackground,
            focusedContainerColor = MyTvColors.SurfaceFocused,
            focusedContentColor = MyTvColors.OnBackground,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, MyTvColors.Accent))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SingleLineText(
                    "${episode.number}.  ${episode.title}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                episode.durationSec?.let {
                    Text(formatDuration(it), style = MaterialTheme.typography.labelMedium, color = MyTvColors.OnSurfaceMuted)
                }
            }
            episode.plot?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MyTvColors.OnSurfaceMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (resume != null && ResumePolicy.startPosition(resume) > 0) ProgramProgress(resume.progress)
        }
    }
}

// ======================================================================= Commun

@Composable
private fun Poster(url: String?, title: String, modifier: Modifier) {
    val context = LocalContext.current
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(10.dp))
            .background(MyTvColors.Surface),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = MyTvColors.OnSurfaceMuted, modifier = Modifier.padding(10.dp))
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(url).size(Size(400, 600)).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Text(
        "$label : $value",
        style = MaterialTheme.typography.bodyMedium,
        color = MyTvColors.OnSurfaceMuted,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun CenteredText(text: String) {
    Box(Modifier.fillMaxSize().background(MyTvColors.Background), contentAlignment = Alignment.Center) {
        Text(text, color = MyTvColors.OnSurfaceMuted, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun FailedDetails(message: String, onRetry: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        focus.tryRequestFocus()
    }
    Box(Modifier.fillMaxSize().background(MyTvColors.Background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(message, style = MaterialTheme.typography.titleMedium)
            Button(onClick = onRetry, modifier = Modifier.focusRequester(focus)) { Text("Réessayer") }
        }
    }
}

/** 6130 s -> « 1 h 42 min » ; 2700 s -> « 45 min ». */
fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 && m > 0 -> "$h h $m min"
        h > 0 -> "$h h"
        else -> "${m.coerceAtLeast(1)} min"
    }
}

/** Position de lecture : « 12:34 » ou « 1:02:03 ». */
fun formatPosition(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
