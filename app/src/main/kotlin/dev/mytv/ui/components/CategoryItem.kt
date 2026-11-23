package dev.mytv.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.mytv.domain.model.Category
import dev.mytv.ui.theme.MyTvColors

/**
 * Ligne de la colonne de catégories (Live TV, films, séries). La catégorie est sélectionnée dès
 * qu'elle reçoit le focus : pas besoin d'appuyer sur OK pour voir son contenu.
 */
@Composable
fun CategoryItem(
    category: Category,
    selected: Boolean,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onFocused,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) MyTvColors.Surface else Color.Transparent,
            focusedContainerColor = MyTvColors.Accent,
            contentColor = if (selected) MyTvColors.OnBackground else MyTvColors.OnSurfaceMuted,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        val prefix = when (category.kind) {
            Category.Kind.FAVORITES -> "★  "
            Category.Kind.ALL -> "≡  "
            Category.Kind.PORTAL -> ""
        }
        Text(
            prefix + category.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
        )
    }
}
