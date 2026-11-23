package dev.mytv.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.mytv.domain.model.ResumePoint
import dev.mytv.ui.common.tryRequestFocus
import dev.mytv.ui.components.Clock
import dev.mytv.ui.components.PosterCard
import dev.mytv.ui.components.PrivacyBadge
import dev.mytv.ui.theme.MyTvColors

/** Destinations proposées par l'accueil. */
enum class HomeSection { LIVE, MOVIES, SERIES, SEARCH }

/**
 * Accueil : quatre grandes tuiles (Live TV, Films, Séries, Recherche) puis « Continuer à regarder ».
 * Le focus part sur « Live TV » : un simple OK suffit pour regarder la télé.
 */
@Composable
fun HomeScreen(
    onSection: (HomeSection) -> Unit,
    onResume: (ResumePoint) -> Unit,
    onChangePortal: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()
    val privacy by viewModel.privacy.collectAsStateWithLifecycle()
    val liveFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { liveFocus.tryRequestFocus() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MyTvColors.Background)
            .padding(horizontal = 48.dp, vertical = 27.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("MyTV", style = MaterialTheme.typography.displaySmall)
                PrivacyBadge(privacy.vpnActive, privacy.vpnRequired, privacy.portalEncrypted)
            }
            // En haut à droite : toujours visible, quelle que soit la longueur de l'écran.
            OutlinedButton(onClick = onChangePortal) { Text("Portail") }
            Spacer(Modifier.width(24.dp))
            Clock(style = MaterialTheme.typography.displaySmall)
        }
        Spacer(Modifier.height(20.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.focusRestorer(),
        ) {
            SectionTile("📺", "Live TV", { onSection(HomeSection.LIVE) }, Modifier.focusRequester(liveFocus))
            SectionTile("🎬", "Films", { onSection(HomeSection.MOVIES) })
            SectionTile("🎞", "Séries", { onSection(HomeSection.SERIES) })
            SectionTile("🔍", "Recherche", { onSection(HomeSection.SEARCH) })
        }

        Spacer(Modifier.height(22.dp))
        if (continueWatching.isNotEmpty()) {
            Text("Continuer à regarder", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
                modifier = Modifier.focusRestorer(),
            ) {
                items(continueWatching, key = { it.key }) { point ->
                    Column(Modifier.width(104.dp)) {
                        PosterCard(
                            title = point.title,
                            imageUrl = point.imageUrl,
                            progress = point.progress,
                            onClick = { onResume(point) },
                        )
                        point.subtitle?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelMedium,
                                color = MyTvColors.OnSurfaceMuted,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        } else {
            Text(
                "Les films et épisodes commencés apparaîtront ici.",
                style = MaterialTheme.typography.bodyMedium,
                color = MyTvColors.OnSurfaceMuted,
            )
        }
    }
}

@Composable
private fun SectionTile(icon: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(width = 196.dp, height = 100.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MyTvColors.Surface,
            contentColor = MyTvColors.OnBackground,
            focusedContainerColor = MyTvColors.Accent,
            focusedContentColor = androidx.compose.ui.graphics.Color.White,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, MyTvColors.OnBackground))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(icon, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
