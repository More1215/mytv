package dev.mytv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.mytv.domain.model.Category
import dev.mytv.domain.model.Channel
import dev.mytv.player.PlaybackError
import dev.mytv.player.PlaybackUiState
import dev.mytv.player.PlaybackViewModel
import dev.mytv.player.VideoSurface
import dev.mytv.ui.common.tryRequestFocus
import dev.mytv.ui.components.ChannelLogo
import dev.mytv.ui.components.Clock
import dev.mytv.ui.components.ProgramProgress
import dev.mytv.ui.components.SingleLineText
import dev.mytv.ui.components.rememberNow
import dev.mytv.ui.components.timeRange
import dev.mytv.ui.theme.MyTvColors
import kotlinx.coroutines.delay

/**
 * Lecteur plein écran.
 *
 * Télécommande :
 * - Haut / CH+ : chaîne suivante · Bas / CH- : chaîne précédente
 * - Gauche ou OK : sélecteur de chaînes (catégories + chaînes) · Droite : infos du programme
 * - Menu (☰) : ajouter/retirer des favoris
 * - Retour : ferme le sélecteur, sinon revient à la liste (la lecture continue en aperçu)
 */
@Composable
fun PlayerScreen(playback: PlaybackViewModel, onBack: () -> Unit) {
    val state by playback.state.collectAsStateWithLifecycle()
    var browserOpen by remember { mutableStateOf(false) }
    // Incrémenté à chaque événement qui doit (ré)afficher le bandeau d'infos.
    var bannerTrigger by remember { mutableIntStateOf(1) }
    var bannerVisible by remember { mutableStateOf(true) }
    val rootFocus = remember { FocusRequester() }
    val browserFocus = remember { FocusRequester() }
    var rootFocused by remember { mutableStateOf(false) }

    LaunchedEffect(bannerTrigger) {
        bannerVisible = true
        delay(BANNER_DURATION_MS)
        bannerVisible = false
    }
    // Le bandeau réapparaît à chaque changement de chaîne (zap ou sélecteur).
    LaunchedEffect(state.channel?.streamId) { bannerTrigger++ }

    // Sans sélecteur ouvert, la racine porte le focus et reçoit les touches de la télécommande.
    LaunchedEffect(browserOpen) {
        if (!browserOpen) {
            withFrameNanos { }
            rootFocus.tryRequestFocus()
        }
    }

    // Liste de zapping (Haut/Bas) : index de la chaîne surlignée, null = liste fermée.
    var zapIndex by remember { mutableStateOf<Int?>(null) }
    var zapTick by remember { mutableIntStateOf(0) }
    val zapList = state.zapList

    fun moveZap(delta: Int) {
        if (zapList.isEmpty()) return
        val base = zapIndex ?: zapList.indexOfFirst { it.streamId == state.channel?.streamId }.coerceAtLeast(0)
        zapIndex = Math.floorMod(base + delta, zapList.size)
        zapTick++
    }

    fun commitZap() {
        zapIndex?.let { zapList.getOrNull(it) }?.let(playback::zapTo)
        zapIndex = null
    }

    // On s'arrête sur une chaîne ~1 s : elle se lance ; la liste reste un instant puis disparaît.
    LaunchedEffect(zapTick) {
        val index = zapIndex ?: return@LaunchedEffect
        delay(ZAP_COMMIT_MS)
        zapList.getOrNull(index)?.let(playback::zapTo)
        delay(ZAP_LINGER_MS)
        zapIndex = null
    }

    fun back() {
        when {
            browserOpen -> browserOpen = false
            zapIndex != null -> zapIndex = null // ferme la liste sans changer de chaîne
            else -> onBack()
        }
    }

    BackHandler { back() }

    fun openBrowser() {
        playback.openBrowser()
        browserOpen = true
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Retour intercepté avant les enfants : sinon, quand un bouton du panneau d'erreur a le
            // focus, Compose consomme le premier Retour pour « sortir » le focus du panneau et
            // l'utilisateur reste bloqué sur l'erreur.
            .onPreviewKeyEvent { event ->
                if (event.key != Key.Back) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyUp) {
                    back()
                }
                true
            }
            .onFocusChanged { rootFocused = it.isFocused }
            .focusRequester(rootFocus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (browserOpen) {
                    // Filet de sécurité : sélecteur ouvert mais focus resté sur la racine ->
                    // la première touche place le focus dans la liste au lieu d'être perdue.
                    return@onKeyEvent rootFocused && event.key in BROWSER_NAV_KEYS && browserFocus.tryRequestFocus()
                }
                when (event.key) {
                    // Haut/Bas suivent la liste à l'écran ; CH+ / CH- gardent leur sens habituel.
                    Key.DirectionUp, Key.ChannelDown -> { moveZap(-1); true }
                    Key.DirectionDown, Key.ChannelUp -> { moveZap(+1); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter ->
                        { if (zapIndex != null) commitZap() else openBrowser(); true }
                    Key.DirectionLeft -> { zapIndex = null; openBrowser(); true }
                    Key.DirectionRight, Key.Info -> { bannerTrigger++; true }
                    Key.Menu -> { playback.toggleCurrentFavorite(); bannerTrigger++; true }
                    else -> false
                }
            },
    ) {
        val player by playback.playerFlow.collectAsStateWithLifecycle()
        VideoSurface(player, Modifier.fillMaxSize())

        // Masqué sous le sélecteur : il transparaissait au milieu de la liste des chaînes.
        if (state.isBuffering && state.error == null && !browserOpen) {
            Text(
                "Chargement…",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        state.error?.let { error ->
            // Fond noir : sinon la dernière image de la chaîne précédente reste figée derrière
            // le message, ce qui laisse croire que la chaîne en erreur fonctionne.
            Box(Modifier.fillMaxSize().background(Color.Black))
            // Panneau masqué quand le sélecteur est ouvert : ses boutons attireraient le focus
            // hors du sélecteur (piège de focus au D-pad).
            if (!browserOpen) ErrorPanel(
                error = error,
                hasOverlay = browserOpen,
                onRetry = playback::retry,
                onDismiss = { playback.dismissError(); rootFocus.tryRequestFocus() },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        AnimatedVisibility(
            visible = (bannerVisible || state.error != null) && !browserOpen && zapIndex == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            InfoBanner(state)
        }

        zapIndex?.let { index ->
            if (!browserOpen) ZapList(zapList, index, playingId = state.channel?.streamId, modifier = Modifier.align(Alignment.CenterStart))
        }

        if (browserOpen) {
            val categories by playback.categories.collectAsStateWithLifecycle()
            val browsedId by playback.browsedCategoryId.collectAsStateWithLifecycle()
            val channels by playback.browsedChannels.collectAsStateWithLifecycle()
            ChannelBrowser(
                categories = categories,
                browsedCategoryId = browsedId,
                channels = channels,
                playingId = state.channel?.streamId,
                onCategoryFocused = playback::browseCategory,
                onSelect = { channel ->
                    browsedId?.let { playback.play(channel, it) }
                    browserOpen = false
                },
                focusFallback = browserFocus,
                onClose = { browserOpen = false },
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
    }
}

@Composable
private fun InfoBanner(state: PlaybackUiState) {
    val channel = state.channel ?: return
    val now = rememberNow()
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
            .padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(channel.number.toString(), style = MaterialTheme.typography.headlineSmall, color = MyTvColors.Accent)
            Spacer(Modifier.width(16.dp))
            ChannelLogo(channel.logoUrl, channel.name, size = 52.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SingleLineText(channel.name, style = MaterialTheme.typography.headlineSmall)
                    if (channel.isFavorite) Text("  ★", style = MaterialTheme.typography.headlineSmall, color = Color(0xFFFFC94D))
                }
                val current = state.nowNext.now
                if (current != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SingleLineText(current.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(12.dp))
                        Text(current.timeRange(), style = MaterialTheme.typography.labelMedium, color = MyTvColors.OnSurfaceMuted)
                    }
                    Spacer(Modifier.height(6.dp))
                    ProgramProgress(current.progressAt(now), Modifier.width(520.dp))
                } else {
                    Text("Pas d'information de programme", color = MyTvColors.OnSurfaceMuted)
                }
                state.nowNext.next?.let {
                    Spacer(Modifier.height(4.dp))
                    SingleLineText(
                        "Ensuite : ${it.title} (${it.timeRange()})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MyTvColors.OnSurfaceMuted,
                    )
                }
            }
            // Heure courante, bien visible : c'est ce qu'on cherche souvent en ouvrant le bandeau.
            Clock(style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(start = 24.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "▲▼ Liste des chaînes   ·   ◀ ou OK Toutes les chaînes   ·   ▶ Infos   ·   ☰ Favori",
            style = MaterialTheme.typography.labelMedium,
            color = MyTvColors.OnSurfaceMuted,
        )
    }
}

/**
 * Sélecteur de chaînes sans quitter le plein écran : catégories à gauche, chaînes à droite.
 * Il s'ouvre sur la catégorie et la chaîne en cours ; Gauche passe aux catégories, Droite revient
 * aux chaînes puis, depuis les chaînes, referme le sélecteur. OK lance la chaîne (le zapping
 * continue ensuite dans sa catégorie).
 */
@Composable
private fun ChannelBrowser(
    categories: List<Category>,
    browsedCategoryId: String?,
    channels: List<Channel>?,
    playingId: Long?,
    onCategoryFocused: (String) -> Unit,
    onSelect: (Channel) -> Unit,
    /** Posé sur la liste des chaînes : y envoie le focus (première chaîne visible). */
    focusFallback: FocusRequester,
    /** Droite depuis la liste des chaînes : on referme et on retrouve la vidéo. */
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val channelState = rememberLazyListState()
    val categoryState = rememberLazyListState()
    val initialFocus = remember { FocusRequester() }
    var initialDone by remember { mutableStateOf(false) }
    val playingIndex = channels?.indexOfFirst { it.streamId == playingId } ?: -1
    val targetIndex = playingIndex.coerceAtLeast(0)

    // À l'ouverture : focus sur la chaîne en cours (ou la première), catégorie visible.
    // Avec un gros portail, la liste met plusieurs images à se mettre en place : une seule
    // tentative échouait et laissait le sélecteur SANS focus (flèches et OK inopérants).
    // On réessaie donc pendant ~1 s, puis on se rabat sur la première chaîne visible.
    LaunchedEffect(channels) {
        if (initialDone || channels.isNullOrEmpty()) return@LaunchedEffect
        categoryState.scrollToItem(categories.indexOfFirst { it.id == browsedCategoryId }.coerceAtLeast(0))
        channelState.scrollToItem((targetIndex - 3).coerceAtLeast(0))
        repeat(60) {
            withFrameNanos { }
            if (initialFocus.tryRequestFocus()) {
                initialDone = true
                return@LaunchedEffect
            }
        }
        initialDone = focusFallback.tryRequestFocus()
    }
    // Nouvelle catégorie parcourue : la liste repart du haut.
    LaunchedEffect(browsedCategoryId) {
        if (initialDone) channelState.scrollToItem(0)
    }

    Row(
        modifier
            .fillMaxHeight()
            .background(Color(0xF00B0F14))
            .padding(vertical = 24.dp, horizontal = 16.dp),
    ) {
        // ------------------------------------------------ Catégories
        LazyColumn(
            state = categoryState,
            modifier = Modifier
                .width(250.dp)
                .fillMaxHeight()
                // Bord gauche du sélecteur : rien au-delà, le focus ne doit pas en sortir.
                .focusProperties { left = FocusRequester.Cancel }
                .focusRestorer(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(4.dp),
        ) {
            itemsIndexed(categories, key = { _, c -> c.id }) { _, category ->
                val selected = category.id == browsedCategoryId
                Surface(
                    onClick = { onCategoryFocused(category.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (it.isFocused) onCategoryFocused(category.id) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (selected) MyTvColors.Surface else Color.Transparent,
                        contentColor = if (selected) MyTvColors.OnBackground else MyTvColors.OnSurfaceMuted,
                        focusedContainerColor = MyTvColors.Accent,
                        focusedContentColor = Color.White,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                ) {
                    SingleLineText(
                        when (category.kind) {
                            Category.Kind.FAVORITES -> "★  ${category.name}"
                            Category.Kind.ALL -> "≡  ${category.name}"
                            Category.Kind.PORTAL -> category.name
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    )
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        // ------------------------------------------------ Chaînes
        Column(Modifier.width(400.dp).fillMaxHeight()) {
            when {
                channels == null -> Text("Chargement…", color = MyTvColors.OnSurfaceMuted, modifier = Modifier.padding(12.dp))
                channels.isEmpty() -> Text("Aucune chaîne ici.", color = MyTvColors.OnSurfaceMuted, modifier = Modifier.padding(12.dp))
                else -> LazyColumn(
                    state = channelState,
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focusFallback)
                        // Droite = retour à la vidéo (symétrique de Gauche qui a ouvert le sélecteur).
                        .onPreviewKeyEvent { e ->
                            if (e.key == Key.DirectionRight && e.type == KeyEventType.KeyDown) {
                                onClose()
                                true
                            } else {
                                false
                            }
                        }
                        .focusProperties { right = FocusRequester.Cancel }
                        .focusRestorer(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(4.dp),
                ) {
                    itemsIndexed(channels, key = { _, c -> c.streamId }) { index, channel ->
                        Surface(
                            onClick = { onSelect(channel) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .then(if (!initialDone && index == targetIndex) Modifier.focusRequester(initialFocus) else Modifier),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color.Transparent,
                                contentColor = MyTvColors.OnBackground,
                                focusedContainerColor = MyTvColors.Accent,
                                focusedContentColor = Color.White,
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                        ) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                ChannelLogo(channel.logoUrl, channel.name, size = 34.dp)
                                Spacer(Modifier.width(10.dp))
                                // Pas de couleur forcée : le texte suit la Surface (lisible focusé ou non).
                                SingleLineText(
                                    (if (channel.streamId == playingId) "▶ " else "") + channel.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorPanel(
    error: PlaybackError,
    hasOverlay: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(error) {
        if (!hasOverlay) {
            withFrameNanos { }
            retryFocus.tryRequestFocus()
        }
    }
    val message = when (error) {
        PlaybackError.STREAM_UNAVAILABLE -> "Cette chaîne ne répond pas pour le moment."
        PlaybackError.NETWORK -> "Problème de connexion au serveur."
    }
    Column(
        modifier
            .background(Color(0xEE151B23), RoundedCornerShape(12.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(message, style = MaterialTheme.typography.titleMedium)
        Text("▲▼ pour changer de chaîne", style = MaterialTheme.typography.labelMedium, color = MyTvColors.OnSurfaceMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus)) { Text("Réessayer") }
            OutlinedButton(onClick = onDismiss) { Text("Fermer") }
        }
    }
}

private const val BANNER_DURATION_MS = 5_000L
private const val ZAP_COMMIT_MS = 900L
private const val ZAP_LINGER_MS = 1_500L

/**
 * Liste de zapping transparente (Haut/Bas en plein écran). Sans focus ni liste paresseuse :
 * seules les 7 lignes autour de la sélection sont dessinées, en boucle aux extrémités.
 * Très léger, même sur une petite box.
 */
@Composable
private fun ZapList(channels: List<Channel>, selected: Int, playingId: Long?, modifier: Modifier = Modifier) {
    if (channels.isEmpty()) return
    val window = minOf(ZAP_WINDOW, channels.size)
    val half = window / 2
    Column(
        modifier
            .fillMaxHeight()
            .width(460.dp)
            .background(Brush.horizontalGradient(listOf(Color(0xE6000000), Color(0x99000000), Color.Transparent)))
            .padding(start = 32.dp, end = 48.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        for (offset in -half until window - half) {
            val channel = channels[Math.floorMod(selected + offset, channels.size)]
            val isSelected = offset == 0
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(if (isSelected) MyTvColors.Accent else Color.Transparent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    channel.number.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isSelected) Color.White else MyTvColors.OnSurfaceMuted,
                    modifier = Modifier.width(52.dp),
                )
                ChannelLogo(channel.logoUrl, channel.name, size = 36.dp)
                Spacer(Modifier.width(10.dp))
                SingleLineText(
                    (if (channel.streamId == playingId) "▶ " else "") + channel.name,
                    style = if (isSelected) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) Color.White else Color(0xFFE6E9EE),
                )
            }
        }
        Text("OK : regarder · Retour : fermer", style = MaterialTheme.typography.labelMedium, color = MyTvColors.OnSurfaceMuted)
    }
}

private const val ZAP_WINDOW = 7

private val BROWSER_NAV_KEYS = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
)
