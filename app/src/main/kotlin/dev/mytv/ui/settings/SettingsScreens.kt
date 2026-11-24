package dev.mytv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
import dev.mytv.domain.model.BufferProfile
import dev.mytv.domain.model.DecoderMode
import dev.mytv.domain.model.LiveFormat
import dev.mytv.ui.common.tryRequestFocus
import dev.mytv.ui.components.SingleLineText
import dev.mytv.ui.theme.MyTvColors
import dev.mytv.update.UpdateState

enum class SettingsSection { PORTALS, PLAYER, PRIVACY, BACKUP, SUPPORT }

// ======================================================================= Hub

@Composable
fun SettingsScreen(onSection: (SettingsSection) -> Unit, updates: UpdateSettingsViewModel = hiltViewModel()) {
    val update by updates.state.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.tryRequestFocus() }
    Page("Réglages") {
        SettingsRow(
            title = "Portails",
            subtitle = "Ajouter, changer de portail actif ou en supprimer un",
            onClick = { onSection(SettingsSection.PORTALS) },
            modifier = Modifier.focusRequester(first),
        )
        SettingsRow("Lecteur", "Format des chaînes, mémoire tampon, décodage vidéo", { onSection(SettingsSection.PLAYER) })
        SettingsRow("Confidentialité", "VPN exigé ou non, ce que ton fournisseur d'accès peut voir", { onSection(SettingsSection.PRIVACY) })
        SettingsRow("Sauvegarde et restauration", "Portails, favoris et reprises, via ton PC ou ton téléphone", { onSection(SettingsSection.BACKUP) })
        SettingsRow(
            title = "Mises à jour · version ${updates.currentVersion}",
            subtitle = when (val s = update) {
                UpdateState.Idle -> "OK pour vérifier s'il existe une nouvelle version"
                UpdateState.Checking -> "Vérification…"
                UpdateState.UpToDate -> "✓ Tu as la dernière version"
                is UpdateState.Available -> "⬆ Version ${s.update.version} disponible : OK pour installer"
                is UpdateState.Downloading -> "⬇ Téléchargement… ${if (s.progress >= 0) "${(s.progress * 100).toInt()} %" else ""}"
                is UpdateState.Ready -> "Version ${s.update.version} téléchargée : OK pour installer"
                is UpdateState.Failed -> s.message
            },
            onClick = updates::onClick,
        )
        SettingsRow("Support", "Aide à distance : code d'accès pour être dépanné depuis un ordinateur", { onSection(SettingsSection.SUPPORT) })
    }
}

// ======================================================================= Support

@Composable
fun SupportScreen(onAccessCode: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.tryRequestFocus() }
    Page("Support") {
        SettingsRow(
            title = "Code d'accès",
            subtitle = "Affiche un code : la personne qui t'aide voit ton écran et pilote MyTV depuis son ordinateur, même sans être chez toi",
            onClick = onAccessCode,
            modifier = Modifier.focusRequester(first),
        )
        Text("MyTV version ${dev.mytv.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = MyTvColors.OnSurfaceMuted)
    }
}

// ======================================================================= Portails

@Composable
fun PortalsScreen(
    onPortalActivated: () -> Unit,
    onAddPortal: () -> Unit,
    onNoPortalLeft: () -> Unit,
    viewModel: PortalsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }
    LaunchedEffect(state.portals.isNotEmpty()) {
        withFrameNanos { }
        first.tryRequestFocus()
    }
    LaunchedEffect(state.confirmDelete) {
        if (state.confirmDelete != null) {
            withFrameNanos { }
            confirmFocus.tryRequestFocus()
        }
    }
    BackHandler(enabled = state.confirmDelete != null) { viewModel.cancelDelete() }

    Box {
        Page("Portails", scrollable = false) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(4.dp), modifier = Modifier.weight(1f, fill = false)) {
                items(state.portals, key = { it.id }) { portal ->
                    val active = portal.id == state.activeId
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingsRow(
                            title = (if (active) "● " else "") + portal.name,
                            subtitle = "${portal.serverUrl.substringAfter("://")}  ·  ${portal.username}" +
                                (if (portal.isEncrypted) "  ·  🔒 HTTPS" else "") +
                                (if (active) "  ·  actif" else "  ·  OK pour l'utiliser"),
                            onClick = { if (!active) viewModel.activate(portal, onPortalActivated) },
                            modifier = Modifier
                                .weight(1f)
                                .then(if (portal == state.portals.first()) Modifier.focusRequester(first) else Modifier),
                            highlighted = active,
                        )
                        OutlinedButton(onClick = { viewModel.askDelete(portal) }) { Text("Supprimer") }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAddPortal) { Text("+  Ajouter un portail") }
        }

        state.confirmDelete?.let { portal ->
            Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                Column(
                    Modifier
                        .width(620.dp)
                        .background(MyTvColors.Surface, RoundedCornerShape(14.dp))
                        .padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Supprimer « ${portal.name} » ?", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Ses chaînes, son guide, ses films et séries, ses favoris et ses reprises de lecture seront effacés de cet appareil.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MyTvColors.OnSurfaceMuted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Le focus va sur « Annuler » : une suppression ne doit jamais se faire par un OK machinal.
                        Button(onClick = viewModel::cancelDelete, modifier = Modifier.focusRequester(confirmFocus)) { Text("Annuler") }
                        OutlinedButton(onClick = {
                            viewModel.confirmDelete { left -> if (!left) onNoPortalLeft() }
                        }) { Text("Supprimer") }
                    }
                }
            }
        }
    }
}

// ======================================================================= Lecteur

@Composable
fun PlayerSettingsScreen(viewModel: PlayerSettingsViewModel = hiltViewModel()) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.tryRequestFocus() }

    Page("Lecteur") {
        Text("OK fait défiler les choix. Les changements s'appliquent tout de suite.", color = MyTvColors.OnSurfaceMuted)
        Spacer(Modifier.height(6.dp))
        SettingsRow(
            title = "Format des chaînes : ${s.liveFormat.label}",
            subtitle = when (s.liveFormat) {
                LiveFormat.TS -> "Flux direct, zapping le plus rapide. Recommandé."
                LiveFormat.HLS -> "Découpé en segments : plus tolérant aux connexions instables, zapping un peu plus lent."
            },
            onClick = { viewModel.update { it.copy(liveFormat = it.liveFormat.next()) } },
            modifier = Modifier.focusRequester(first),
        )
        SettingsRow(
            title = "Mémoire tampon : ${s.buffer.label}",
            subtitle = when (s.buffer) {
                BufferProfile.FAST -> "L'image démarre en ~1 s. Idéal avec une bonne connexion."
                BufferProfile.BALANCED -> "Démarrage ~2,5 s, moins de coupures."
                BufferProfile.STABLE -> "Démarrage ~5 s, pour les connexions lentes ou un portail qui sature le soir."
            },
            onClick = { viewModel.update { it.copy(buffer = it.buffer.next()) } },
        )
        SettingsRow(
            title = "Décodage vidéo : ${s.decoder.label}",
            subtitle = when (s.decoder) {
                DecoderMode.HARDWARE -> "Puce vidéo du Fire TV : fluide et économe."
                DecoderMode.SOFTWARE -> "Plus lent : à n'utiliser que si une chaîne s'affiche mal (image verte, figée…)."
            },
            onClick = { viewModel.update { it.copy(decoder = it.decoder.next()) } },
        )
        SettingsRow(
            title = "Relancer la dernière chaîne au démarrage : ${if (s.resumeLastChannel) "Oui" else "Non"}",
            subtitle = if (s.resumeLastChannel) "L'appli s'ouvre directement sur la télé." else "L'appli s'ouvre sur l'accueil.",
            onClick = { viewModel.update { it.copy(resumeLastChannel = !it.resumeLastChannel) } },
        )
    }
}

// ======================================================================= Confidentialité

@Composable
fun PrivacySettingsScreen(viewModel: PrivacySettingsViewModel = hiltViewModel()) {
    val p by viewModel.protection.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.tryRequestFocus() }

    Page("Confidentialité") {
        Text(
            if (p.vpnActive) "🔒 Un VPN est actif : ton fournisseur d'accès ne voit qu'une connexion chiffrée."
            else "⚠ Aucun VPN actif sur cet appareil.",
            color = if (p.vpnActive) MyTvColors.Success else MyTvColors.Error,
        )
        Spacer(Modifier.height(6.dp))
        SettingsRow(
            title = "Exiger un VPN : ${if (p.vpnRequired) "Oui" else "Non"}",
            subtitle = if (p.vpnRequired) {
                "Sans VPN, MyTV ne fait aucune connexion. Le plus sûr."
            } else {
                "MyTV se connecte même sans VPN : ton fournisseur d'accès voit les serveurs contactés."
            },
            onClick = viewModel::toggleRequired,
            modifier = Modifier.focusRequester(first),
        )
        Text(
            "Dans tous les cas, les adresses sont résolues en DNS chiffré et le portail passe en HTTPS dès qu'il le propose.",
            color = MyTvColors.OnSurfaceMuted,
        )
    }
}

// ======================================================================= Commun

@Composable
internal fun Page(title: String, scrollable: Boolean = true, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MyTvColors.Background)
            // Défile quand le focus descend sous le bas de l'écran (ex. ligne Support).
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 48.dp, vertical = 27.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
internal fun SettingsRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (highlighted) MyTvColors.SurfaceFocused else MyTvColors.Surface,
            contentColor = MyTvColors.OnBackground,
            focusedContainerColor = MyTvColors.SurfaceFocused,
            focusedContentColor = MyTvColors.OnBackground,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, MyTvColors.Accent))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SingleLineText(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MyTvColors.OnSurfaceMuted, maxLines = 2)
        }
    }
}
