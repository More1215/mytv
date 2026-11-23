package dev.mytv.ui.vod

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.mytv.domain.model.SyncState
import dev.mytv.domain.usecase.VodKind
import dev.mytv.ui.common.toMessage
import dev.mytv.ui.common.tryRequestFocus
import dev.mytv.ui.components.CategoryItem
import dev.mytv.ui.components.Clock
import dev.mytv.ui.components.PosterCard
import dev.mytv.ui.theme.MyTvColors

/**
 * Films ou séries : catégories à gauche, grille d'affiches à droite.
 * Retour depuis la grille ramène aux catégories ; depuis les catégories, revient à l'accueil.
 */
@Composable
fun VodBrowseScreen(
    onOpen: (kind: VodKind, id: Long) -> Unit,
    onSearch: () -> Unit,
    viewModel: VodBrowseViewModel = hiltViewModel(),
) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val selected by viewModel.selectedCategoryId.collectAsStateWithLifecycle()
    val posters = viewModel.pages.collectAsLazyPagingItems()
    val count by viewModel.count.collectAsStateWithLifecycle()
    val sync by viewModel.sync.collectAsStateWithLifecycle()

    val gridState = rememberLazyGridState()
    val firstItemFocus = remember { FocusRequester() }
    val selectedCategoryFocus = remember { FocusRequester() }
    var gridHasFocus by remember { mutableStateOf(false) }
    var initialFocusDone by remember { mutableStateOf(false) }

    val hasItems = posters.itemCount > 0
    LaunchedEffect(hasItems) {
        if (initialFocusDone || !hasItems) return@LaunchedEffect
        // Grosse base sur machine lente : la grille peut mettre plusieurs images à apparaître.
        repeat(60) {
            withFrameNanos { }
            if (firstItemFocus.tryRequestFocus()) {
                initialFocusDone = true
                return@LaunchedEffect
            }
        }
    }
    // Nouvelle catégorie : la grille repart du haut.
    LaunchedEffect(selected) { if (initialFocusDone && hasItems) gridState.scrollToItem(0) }
    val loading = posters.loadState.refresh is LoadState.Loading

    BackHandler(enabled = gridHasFocus) { selectedCategoryFocus.tryRequestFocus() }

    val isMovies = viewModel.kind == VodKind.MOVIES
    Row(
        Modifier
            .fillMaxSize()
            .background(MyTvColors.Background)
            .padding(horizontal = 32.dp, vertical = 20.dp),
    ) {
        Column(Modifier.width(230.dp).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (isMovies) "Films" else "Séries", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Clock(style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                modifier = Modifier.weight(1f).focusRestorer(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                itemsIndexed(categories, key = { _, c -> c.id }) { _, category ->
                    CategoryItem(
                        category = category,
                        selected = category.id == selected,
                        onFocused = { viewModel.onCategoryFocused(category.id) },
                        modifier = if (category.id == selected) Modifier.focusRequester(selectedCategoryFocus) else Modifier,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSearch) { Text("Rechercher") }
                OutlinedButton(onClick = viewModel::refresh) { Text("Actualiser") }
            }
        }

        Spacer(Modifier.width(20.dp))

        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    categories.firstOrNull { it.id == selected }?.name.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                count?.let { n ->
                    val noun = if (isMovies) "film" else "série"
                    Text("$n $noun${if (n > 1) "s" else ""}", style = MaterialTheme.typography.labelMedium, color = MyTvColors.OnSurfaceMuted)
                }
            }
            when (val s = sync) {
                SyncState.Running -> Text("Synchronisation en cours…", style = MaterialTheme.typography.labelMedium, color = MyTvColors.OnSurfaceMuted)
                is SyncState.Failed -> Text(
                    "Mise à jour impossible : ${s.error.toMessage()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MyTvColors.Error,
                    maxLines = 1,
                )
                SyncState.Idle -> Unit
            }
            Spacer(Modifier.height(8.dp))
            when {
                !hasItems && (loading || sync == SyncState.Running) ->
                    Message(if (isMovies) "Chargement des films…" else "Chargement des séries…")
                !hasItems -> Message("Rien dans cette catégorie.")
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRestorer()
                        .onFocusChanged { gridHasFocus = it.hasFocus },
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    // Marge pour que l'agrandissement de l'affiche focusée ne soit pas rogné.
                    contentPadding = PaddingValues(8.dp),
                ) {
                    items(count = posters.itemCount, key = posters.itemKey { it.id }) { index ->
                        val item = posters[index]
                        val mod = if (index == 0) Modifier.focusRequester(firstItemFocus) else Modifier
                        if (item == null) {
                            // Emplacement pas encore lu (défilement rapide) : case vide mais
                            // focalisable, pour que le D-pad continue de défiler sans blocage.
                            PosterCard(title = "", imageUrl = null, onClick = {}, modifier = mod)
                        } else {
                            PosterCard(
                                title = item.title,
                                imageUrl = item.imageUrl,
                                progress = item.progress,
                                onClick = { onOpen(viewModel.kind, item.id) },
                                modifier = mod,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.TopCenter) {
        Text(text, color = MyTvColors.OnSurfaceMuted, style = MaterialTheme.typography.bodyLarge)
    }
}
