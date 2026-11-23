package dev.mytv.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.mytv.domain.usecase.VodKind
import dev.mytv.ui.common.tryRequestFocus
import dev.mytv.ui.components.PosterCard
import dev.mytv.ui.components.TvTextField
import dev.mytv.ui.theme.MyTvColors

/**
 * Recherche : champ texte (OK pour ouvrir le clavier), puis une rangée « Films » et une rangée
 * « Séries ». Bas depuis le champ descend dans les résultats.
 */
@Composable
fun SearchScreen(
    onOpen: (kind: VodKind, id: Long) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { fieldFocus.tryRequestFocus() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MyTvColors.Background)
            .padding(horizontal = 48.dp, vertical = 27.dp),
    ) {
        Text("Recherche", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        TvTextField(
            label = "Titre d'un film ou d'une série",
            value = query,
            onValueChange = viewModel::onQueryChange,
            placeholder = "Appuie sur OK pour écrire",
            imeAction = ImeAction.Search,
            focusRequester = fieldFocus,
            modifier = Modifier.width(620.dp),
        )
        Spacer(Modifier.height(18.dp))

        val r = results
        when {
            query.trim().length < 2 -> Hint("Tape au moins 2 lettres. Les accents et majuscules n'ont pas d'importance.")
            r == null -> Hint("Recherche…")
            r.isEmpty -> Hint("Aucun résultat pour « ${query.trim()} ».")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (r.movies.isNotEmpty()) {
                    item(key = "movies") {
                        ResultRow("Films (${r.movies.size})") {
                            items(r.movies, key = { "m${it.streamId}" }) { movie ->
                                PosterCard(
                                    title = movie.name,
                                    imageUrl = movie.posterUrl,
                                    onClick = { onOpen(VodKind.MOVIES, movie.streamId) },
                                    modifier = Modifier.width(118.dp),
                                )
                            }
                        }
                    }
                }
                if (r.series.isNotEmpty()) {
                    item(key = "series") {
                        ResultRow("Séries (${r.series.size})") {
                            items(r.series, key = { "s${it.seriesId}" }) { series ->
                                PosterCard(
                                    title = series.name,
                                    imageUrl = series.coverUrl,
                                    onClick = { onOpen(VodKind.SERIES, series.seriesId) },
                                    modifier = Modifier.width(118.dp),
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
private fun ResultRow(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(8.dp),
            modifier = Modifier.focusRestorer(),
            content = content,
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MyTvColors.OnSurfaceMuted)
}
