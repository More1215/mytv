package dev.mytv.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.mytv.domain.model.Channel
import dev.mytv.domain.model.EpgProgram
import dev.mytv.player.PlaybackViewModel
import dev.mytv.ui.common.tryRequestFocus
import dev.mytv.ui.components.ChannelLogo
import dev.mytv.ui.components.SingleLineText
import dev.mytv.ui.components.Clock
import dev.mytv.ui.components.formatDay
import dev.mytv.ui.components.formatTime
import dev.mytv.ui.components.rememberNow
import dev.mytv.ui.components.timeRange
import dev.mytv.ui.guide.GuideViewModel.Companion.HALF_HOUR_MS
import dev.mytv.ui.guide.GuideViewModel.Companion.WINDOW_MS
import dev.mytv.ui.theme.MyTvColors

private val ROW_HEIGHT = 54.dp
private val CHANNEL_COLUMN = 210.dp

/** Programme focusé, ou case « pas d'info » d'une chaîne (program == null). */
private data class GuideFocus(val channel: Channel, val program: EpgProgram?)

/**
 * Grille du guide. Navigation :
 * - Haut/Bas : chaîne précédente/suivante ; Gauche/Droite : programme précédent/suivant,
 *   la fenêtre horaire glisse de 30 min quand on atteint un bord ;
 * - OK : regarde la chaîne ; Retour : revient à la liste.
 */
@Composable
fun GuideScreen(
    playback: PlaybackViewModel,
    onOpenPlayer: () -> Unit,
    viewModel: GuideViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val now = rememberNow()
    var focused by remember { mutableStateOf<GuideFocus?>(null) }
    val listState = rememberLazyListState()
    val initialFocus = remember { FocusRequester() }
    var initialRow by remember { mutableStateOf(-1) }
    var initialDone by remember { mutableStateOf(false) }

    val rows = state.rows
    val windowStart = state.windowStartMs
    val windowEnd = windowStart + WINDOW_MS

    // Focus initial : la chaîne en lecture (sinon la première), sur le programme en cours.
    LaunchedEffect(rows) {
        if (initialDone || rows.isNullOrEmpty()) return@LaunchedEffect
        val playingId = playback.state.value.channel?.streamId
        val index = rows.indexOfFirst { it.channel.streamId == playingId }.coerceAtLeast(0)
        initialRow = index
        listState.scrollToItem((index - 2).coerceAtLeast(0))
        withFrameNanos { }
        initialDone = initialFocus.tryRequestFocus()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MyTvColors.Background)
            .padding(horizontal = 32.dp, vertical = 20.dp),
    ) {
        // ------------------------------------------------ En-tête : programme focusé
        Row(Modifier.fillMaxWidth().height(96.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Guide TV · ${state.categoryName}", style = MaterialTheme.typography.labelLarge, color = MyTvColors.OnSurfaceMuted)
                val program = focused?.program
                if (program != null) {
                    SingleLineText(program.title, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${focused!!.channel.name}  ·  ${dayLabel(program.startMs)} ${program.timeRange()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MyTvColors.Accent,
                    )
                    program.description?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MyTvColors.OnSurfaceMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Text(focused?.channel?.name ?: "", style = MaterialTheme.typography.headlineSmall)
                }
            }
            Clock(style = MaterialTheme.typography.headlineSmall)
        }

        // ------------------------------------------------ Règle horaire
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gridWidth = maxWidth - CHANNEL_COLUMN
            val dpPerMs = gridWidth / WINDOW_MS.toFloat()
            Row(Modifier.fillMaxWidth().height(28.dp)) {
                Text(dayLabel(windowStart), modifier = Modifier.width(CHANNEL_COLUMN), style = MaterialTheme.typography.labelMedium, color = MyTvColors.OnSurfaceMuted)
                Box(Modifier.width(gridWidth)) {
                    var t = windowStart
                    while (t < windowEnd) {
                        Text(
                            formatTime(t),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.offset(x = dpPerMs * (t - windowStart).toFloat()),
                        )
                        t += HALF_HOUR_MS
                    }
                }
            }
        }

        // ------------------------------------------------ Grille
        when {
            rows == null -> Text("Chargement du guide…", color = MyTvColors.OnSurfaceMuted)
            rows.isEmpty() -> Text("Aucune chaîne dans cette catégorie.", color = MyTvColors.OnSurfaceMuted)
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val gridWidth = maxWidth - CHANNEL_COLUMN
                val dpPerMs = gridWidth / WINDOW_MS.toFloat()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().focusRestorer(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    itemsIndexed(rows, key = { _, r -> r.channel.streamId }) { index, row ->
                        GuideRowView(
                            channel = row.channel,
                            programs = row.programs,
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                            nowMs = now,
                            gridWidth = gridWidth,
                            dpPerMs = dpPerMs,
                            initialFocus = if (index == initialRow) initialFocus else null,
                            onFocused = { focused = GuideFocus(row.channel, it) },
                            onShift = viewModel::shiftWindow,
                            onPlay = {
                                playback.play(row.channel, viewModel.categoryId)
                                onOpenPlayer()
                            },
                        )
                    }
                }
                // Repère « maintenant » : trait vertical sur toute la hauteur de la grille.
                if (now in windowStart until windowEnd) {
                    Box(
                        Modifier
                            .offset(x = CHANNEL_COLUMN + dpPerMs * (now - windowStart).toFloat())
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(MyTvColors.Error),
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideRowView(
    channel: Channel,
    programs: List<EpgProgram>,
    windowStart: Long,
    windowEnd: Long,
    nowMs: Long,
    gridWidth: Dp,
    dpPerMs: Dp,
    initialFocus: FocusRequester?,
    onFocused: (EpgProgram?) -> Unit,
    onShift: (Int) -> Unit,
    onPlay: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(vertical = 2.dp)) {
        Row(
            Modifier.width(CHANNEL_COLUMN).fillMaxHeight().padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(channel.number.toString(), modifier = Modifier.width(40.dp), style = MaterialTheme.typography.labelLarge, color = MyTvColors.OnSurfaceMuted)
            ChannelLogo(channel.logoUrl, channel.name, size = 34.dp)
            Spacer(Modifier.width(8.dp))
            SingleLineText(channel.name, style = MaterialTheme.typography.bodyMedium)
        }
        Box(Modifier.width(gridWidth).fillMaxHeight()) {
            val visible = programs.filter { it.endMs > windowStart && it.startMs < windowEnd }
            if (visible.isEmpty()) {
                // Case unique pour que la ligne reste navigable même sans guide.
                ProgramCell(
                    title = "Pas d'information",
                    isNow = false,
                    width = gridWidth,
                    offsetX = 0.dp,
                    modifier = initialFocus?.let { Modifier.focusRequester(it) } ?: Modifier,
                    onFocused = { onFocused(null) },
                    onClick = onPlay,
                    onKey = { false },
                )
            } else {
                val current = visible.indexOfFirst { nowMs in it.startMs until it.endMs }.coerceAtLeast(0)
                visible.forEachIndexed { i, program ->
                    // key() : la cellule focusée garde son identité quand la fenêtre glisse.
                    key(program.startMs) {
                        val start = maxOf(program.startMs, windowStart)
                        val end = minOf(program.endMs, windowEnd)
                        ProgramCell(
                            title = program.title,
                            isNow = nowMs in program.startMs until program.endMs,
                            width = dpPerMs * (end - start).toFloat(),
                            offsetX = dpPerMs * (start - windowStart).toFloat(),
                            modifier = if (i == current && initialFocus != null) Modifier.focusRequester(initialFocus) else Modifier,
                            onFocused = { onFocused(program) },
                            onClick = onPlay,
                            onKey = { key ->
                                // Au bord de la fenêtre, on fait glisser le temps au lieu de sortir de la ligne.
                                when {
                                    key == Key.DirectionRight && i == visible.lastIndex && program.endMs > windowEnd -> { onShift(+1); true }
                                    key == Key.DirectionRight && i == visible.lastIndex -> { onShift(+1); true }
                                    key == Key.DirectionLeft && i == 0 && program.startMs <= windowStart -> { onShift(-1); true }
                                    else -> false
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgramCell(
    title: String,
    isNow: Boolean,
    width: Dp,
    offsetX: Dp,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onKey: (Key) -> Boolean,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .offset(x = offsetX)
            .width((width - 2.dp).coerceAtLeast(4.dp))
            .fillMaxHeight()
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && onKey(e.key) },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            // Programme en cours : fond légèrement plus clair, texte toujours bien lisible.
            containerColor = if (isNow) MyTvColors.SurfaceFocused else MyTvColors.Surface,
            contentColor = MyTvColors.OnBackground,
            focusedContainerColor = MyTvColors.Accent,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
            SingleLineText(title, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun dayLabel(ms: Long): String = formatDay(ms).replaceFirstChar { it.uppercase() }
