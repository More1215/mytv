package dev.mytv.ui.live

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
import dev.mytv.domain.model.Category
import dev.mytv.domain.model.Channel
import dev.mytv.domain.model.NowNext
import dev.mytv.domain.model.SyncState
import dev.mytv.player.PlaybackViewModel
import dev.mytv.player.VideoSurface
import dev.mytv.ui.common.toMessage
import dev.mytv.ui.common.tryRequestFocus
import dev.mytv.ui.components.CategoryItem
import dev.mytv.ui.components.ChannelLogo
import dev.mytv.ui.components.Clock
import dev.mytv.ui.components.PrivacyBadge
import dev.mytv.ui.components.ProgramProgress
import dev.mytv.ui.components.SingleLineText
import dev.mytv.ui.components.rememberNow
import dev.mytv.ui.components.timeRange
import dev.mytv.ui.theme.MyTvColors

/**
 * Écran principal Live TV, façon MyTVOnline :
 * catégories à gauche · chaînes au centre · aperçu vidéo + infos programme à droite.
 *
 * Règles de focus (point critique au D-pad) :
 * - à l'ouverture, le focus va sur la chaîne en cours de lecture, sinon la première de la liste ;
 * - chaque colonne mémorise son dernier élément focusé (focusRestorer) quand on y revient ;
 * - Retour depuis la liste des chaînes ramène sur les catégories ; depuis les catégories, quitte.
 */
@Composable
fun LiveScreen(
    playback: PlaybackViewModel,
    onOpenPlayer: () -> Unit,
    onOpenGuide: (categoryId: String) -> Unit,
    onChangePortal: () -> Unit,
    onSearch: () -> Unit,
    viewModel: LiveViewModel = hiltViewModel(),
) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val selectedCategoryId by viewModel.selectedCategoryId.collectAsStateWithLifecycle()
    val channelList by viewModel.channelList.collectAsStateWithLifecycle()
    val nowNext by viewModel.nowNext.collectAsStateWithLifecycle()
    val sync by viewModel.sync.collectAsStateWithLifecycle()
    val privacy by viewModel.privacy.collectAsStateWithLifecycle()
    val recentChannels by viewModel.recentChannels.collectAsStateWithLifecycle()
    val playbackState by playback.state.collectAsStateWithLifecycle()
    val now = rememberNow()

    var focusedChannel by remember { mutableStateOf<Channel?>(null) }
    var listHasFocus by remember { mutableStateOf(false) }
    val selectedCategoryFocus = remember { FocusRequester() }
    val targetChannelFocus = remember { FocusRequester() }
    var targetChannelIndex by remember { mutableStateOf(-1) }
    var initialFocusDone by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // « Dernières recherches » : affichées dès que « Rechercher » a le focus, et tant qu'on n'est
    // pas revenu sur une catégorie (on peut donc aller à droite dans la liste et lancer une chaîne).
    var showRecent by remember { mutableStateOf(false) }
    var recentHasFocus by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }

    val channels = channelList?.channels
    val playingId = playbackState.channel?.streamId

    // Ouvert depuis le menu : « Toutes les chaînes ». En revenant du lecteur, la liste se cale sur
    // la catégorie de la chaîne en cours (elle a pu être choisie dans le sélecteur du lecteur).
    var returning by rememberSaveable { mutableStateOf(false) }
    val playingCategory = remember {
        if (!returning) Category.ALL_ID else playback.state.value.takeIf { it.channel != null }?.categoryId
    }
    LaunchedEffect(Unit) { returning = true }
    LaunchedEffect(Unit) { playingCategory?.let(viewModel::onCategoryFocused) }

    // Focus initial (une fois par entrée sur l'écran) : chaîne en lecture, sinon première chaîne.
    LaunchedEffect(channels) {
        if (initialFocusDone || channels == null) return@LaunchedEffect
        // On attend la liste de la catégorie de la chaîne en cours. Si cette catégorie a disparu
        // du portail, sa liste arrive vide et le cas « liste vide » ci-dessous prend le relais.
        if (playingCategory != null && channelList?.categoryId != playingCategory) return@LaunchedEffect
        if (channels.isEmpty()) {
            if (categories.isNotEmpty()) {
                withFrameNanos { }
                initialFocusDone = selectedCategoryFocus.tryRequestFocus()
            }
            return@LaunchedEffect
        }
        val index = channels.indexOfFirst { it.streamId == playingId }.coerceAtLeast(0)
        targetChannelIndex = index
        listState.scrollToItem(index)
        withFrameNanos { } // laisse la LazyColumn composer l'élément avant de lui donner le focus
        initialFocusDone = targetChannelFocus.tryRequestFocus()
    }

    // Nouvelle catégorie : la liste repart du haut (sauf au tout premier affichage).
    LaunchedEffect(channelList?.categoryId) {
        if (initialFocusDone) listState.scrollToItem(0)
    }

    BackHandler(enabled = listHasFocus) { selectedCategoryFocus.tryRequestFocus() }
    BackHandler(enabled = recentHasFocus) { searchFocus.tryRequestFocus() }

    Row(
        Modifier
            .fillMaxSize()
            .background(MyTvColors.Background)
            .padding(horizontal = 32.dp, vertical = 20.dp),
    ) {
        // ------------------------------------------------------------ Catégories
        Column(Modifier.width(230.dp).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Live TV", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Clock(style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(4.dp))
            PrivacyBadge(privacy.vpnActive, privacy.vpnRequired, privacy.portalEncrypted)
            Spacer(Modifier.height(10.dp))
            // Recherche par nom ou numéro, dans toutes les catégories.
            Surface(
                onClick = onSearch,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(searchFocus)
                    // Le focus « automatique » d'Android à l'arrivée sur l'écran ne compte pas.
                    .onFocusChanged { if (it.isFocused && initialFocusDone) showRecent = true },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = MyTvColors.Accent,
                    contentColor = MyTvColors.OnSurfaceMuted,
                    focusedContentColor = Color.White,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            ) {
                Text("🔍  Rechercher", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
            Spacer(Modifier.height(4.dp))
            LazyColumn(
                modifier = Modifier.weight(1f).focusRestorer(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                itemsIndexed(categories, key = { _, c -> c.id }) { _, category ->
                    CategoryItem(
                        category = category,
                        selected = category.id == selectedCategoryId,
                        onFocused = {
                            showRecent = false
                            // Au retour sur l'écran, Android donne d'office le focus au premier
                            // élément (Favoris) : ce focus « automatique » ne doit pas écraser la
                            // catégorie de la chaîne en cours tant que le focus initial n'est pas posé.
                            if (initialFocusDone || playingCategory == null) viewModel.onCategoryFocused(category.id)
                        },
                        modifier = if (category.id == selectedCategoryId) Modifier.focusRequester(selectedCategoryFocus) else Modifier,
                    )
                }
            }
        }

        Spacer(Modifier.width(20.dp))

        // ------------------------------------------------------------ Chaînes
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val categoryName = if (showRecent) "Dernières recherches" else categories.firstOrNull { it.id == selectedCategoryId }?.name.orEmpty()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(categoryName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                channels?.takeIf { !showRecent }?.let {
                    Text(
                        if (it.size > 1) "${it.size} chaînes" else "${it.size} chaîne",
                        style = MaterialTheme.typography.labelMedium,
                        color = MyTvColors.OnSurfaceMuted,
                    )
                }
            }
            SyncBanner(sync, onRetry = viewModel::refresh)
            Spacer(Modifier.height(8.dp))

            when {
                showRecent && recentChannels.isEmpty() ->
                    CenteredMessage("Aucune recherche récente.\nOK sur « Rechercher » pour trouver une chaîne par son nom ou son numéro.")
                showRecent -> LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { recentHasFocus = it.hasFocus },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    itemsIndexed(recentChannels, key = { _, c -> "r${c.streamId}" }) { _, channel ->
                        ChannelRow(
                            channel = channel,
                            nowNext = channel.epgChannelId?.let { nowNext[it] } ?: NowNext.EMPTY,
                            nowMs = now,
                            isPlaying = channel.streamId == playingId,
                            onFocused = { focusedChannel = channel },
                            onClick = {
                                playback.play(channel, channel.categoryId ?: Category.ALL_ID)
                                onOpenPlayer()
                            },
                            onLongClick = { viewModel.toggleFavorite(channel) },
                            modifier = Modifier.focusProperties { left = searchFocus },
                        )
                    }
                }
                channels == null || (channels.isEmpty() && sync == SyncState.Running) ->
                    CenteredMessage("Chargement des chaînes…")
                channels.isEmpty() && selectedCategoryId == Category.FAVORITES_ID ->
                    CenteredMessage("Aucun favori pour l'instant.\nAppui long sur OK sur une chaîne pour l'ajouter.")
                channels.isEmpty() -> CenteredMessage("Aucune chaîne dans cette catégorie.")
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .focusRestorer()
                        .onFocusChanged { listHasFocus = it.hasFocus },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    itemsIndexed(channels, key = { _, c -> c.streamId }) { index, channel ->
                        ChannelRow(
                            channel = channel,
                            nowNext = channel.epgChannelId?.let { nowNext[it] } ?: NowNext.EMPTY,
                            nowMs = now,
                            isPlaying = channel.streamId == playingId,
                            onFocused = { focusedChannel = channel },
                            onClick = {
                                playback.play(channel, channelList!!.categoryId)
                                onOpenPlayer()
                            },
                            onLongClick = { viewModel.toggleFavorite(channel) },
                            // ◀ revient toujours sur la catégorie affichée, pas sur celle alignée à l'écran.
                            modifier = (if (index == targetChannelIndex) Modifier.focusRequester(targetChannelFocus) else Modifier)
                                .focusProperties { left = selectedCategoryFocus },
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "OK : regarder  ·  Appui long : favori",
                maxLines = 1,
                style = MaterialTheme.typography.labelMedium,
                color = MyTvColors.OnSurfaceMuted,
            )
        }

        Spacer(Modifier.width(20.dp))

        // ------------------------------------------------------------ Aperçu + infos
        Column(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Colonne étroite : libellés courts, marges internes réduites et une seule ligne,
            // sinon « Réglages » passe sur deux lignes ou est coupé.
            val pad = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { selectedCategoryId?.let(onOpenGuide) }, contentPadding = pad) { Text("Guide", maxLines = 1, softWrap = false) }
                OutlinedButton(onClick = viewModel::refresh, contentPadding = pad) { Text("Actualiser", maxLines = 1, softWrap = false) }
                OutlinedButton(onClick = onChangePortal, contentPadding = pad) { Text("Réglages", maxLines = 1, softWrap = false) }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(169.dp)
                    .background(Color.Black, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (playbackState.channel != null) {
                    val player by playback.playerFlow.collectAsStateWithLifecycle()
                    VideoSurface(player, Modifier.fillMaxSize(), embedded = true)
                } else {
                    Text("Aucune lecture", color = MyTvColors.OnSurfaceMuted)
                }
            }
            playbackState.channel?.let {
                SingleLineText("▶ ${it.number}  ${it.name}", style = MaterialTheme.typography.labelLarge, color = MyTvColors.Accent)
            }
            focusedChannel?.let { channel ->
                // La chaîne focusée peut ne plus être dans la liste affichée (changement de
                // catégorie) : pour la chaîne en lecture, on reprend alors l'EPG du lecteur.
                val details = channel.epgChannelId?.let { nowNext[it] }
                    ?: playbackState.nowNext.takeIf { playbackState.channel?.streamId == channel.streamId }
                    ?: NowNext.EMPTY
                ChannelDetails(channel, details, now)
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    nowNext: NowNext,
    nowMs: Long,
    isPlaying: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .fillMaxWidth()
            .height(62.dp)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MyTvColors.Surface,
            contentColor = MyTvColors.OnBackground,
            focusedContainerColor = MyTvColors.SurfaceFocused,
            // Par défaut le texte focusé prend une couleur sombre, peu lisible sur notre fond.
            focusedContentColor = MyTvColors.OnBackground,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, MyTvColors.Accent)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                channel.number.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = if (isPlaying) MyTvColors.Accent else MyTvColors.OnSurfaceMuted,
                modifier = Modifier.width(44.dp),
            )
            ChannelLogo(channel.logoUrl, channel.name, size = 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SingleLineText(
                        channel.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isPlaying) MyTvColors.Accent else Color.Unspecified,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (channel.isFavorite) Text("  ★", color = Color(0xFFFFC94D))
                }
                val current = nowNext.now
                if (current != null) {
                    SingleLineText(current.title, style = MaterialTheme.typography.labelMedium, color = MyTvColors.OnSurfaceMuted)
                    Spacer(Modifier.height(3.dp))
                    ProgramProgress(current.progressAt(nowMs))
                }
            }
        }
    }
}

@Composable
private fun ChannelDetails(channel: Channel, nowNext: NowNext, nowMs: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel.logoUrl, channel.name, size = 36.dp)
            Spacer(Modifier.width(10.dp))
            SingleLineText(channel.name, style = MaterialTheme.typography.titleMedium)
        }
        val current = nowNext.now
        if (current == null) {
            Text("Pas d'information de programme", color = MyTvColors.OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(current.timeRange(), style = MaterialTheme.typography.labelMedium, color = MyTvColors.OnSurfaceMuted)
            Text(current.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ProgramProgress(current.progressAt(nowMs))
            current.description?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MyTvColors.OnSurfaceMuted, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
        nowNext.next?.let { next ->
            Spacer(Modifier.height(4.dp))
            Text("Ensuite · ${next.timeRange()}", style = MaterialTheme.typography.labelMedium, color = MyTvColors.OnSurfaceMuted)
            SingleLineText(next.title, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SyncBanner(sync: SyncState, onRetry: () -> Unit) {
    when (sync) {
        SyncState.Idle -> Unit
        SyncState.Running -> Text(
            "Synchronisation en cours…",
            style = MaterialTheme.typography.labelMedium,
            color = MyTvColors.OnSurfaceMuted,
        )
        is SyncState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Mise à jour impossible : ${sync.error.toMessage()} (affichage du cache)",
                style = MaterialTheme.typography.labelMedium,
                color = MyTvColors.Error,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            OutlinedButton(onClick = onRetry) { Text("Réessayer") }
        }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.TopCenter) {
        Text(text, color = MyTvColors.OnSurfaceMuted, style = MaterialTheme.typography.bodyLarge)
    }
}
