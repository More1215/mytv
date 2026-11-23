package dev.mytv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

/** Palette sombre, contrastes élevés pour une lecture confortable à 3 mètres. */
object MyTvColors {
    val Background = Color(0xFF0B0F14)
    val Surface = Color(0xFF151B23)
    val SurfaceFocused = Color(0xFF1F2A36)
    val Accent = Color(0xFF2F80ED)
    val OnBackground = Color(0xFFF2F5F8)
    val OnSurfaceMuted = Color(0xFF9AA7B4)
    val Success = Color(0xFF3DDC84)
    val Error = Color(0xFFFF6B6B)
}

private val colorScheme = darkColorScheme(
    primary = MyTvColors.Accent,
    onPrimary = Color.White,
    background = MyTvColors.Background,
    onBackground = MyTvColors.OnBackground,
    surface = MyTvColors.Surface,
    onSurface = MyTvColors.OnBackground,
    surfaceVariant = MyTvColors.SurfaceFocused,
    onSurfaceVariant = MyTvColors.OnSurfaceMuted,
    error = MyTvColors.Error,
    border = MyTvColors.Accent,
)

// Tailles volontairement plus grandes que les défauts : la TV est regardée de loin.
private val typography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 18.sp),
    bodyMedium = TextStyle(fontSize = 16.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 14.sp),
)

@Composable
fun MyTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorScheme, typography = typography) {
        // Hors d'une Surface, le Text TV hérite d'un LocalContentColor noir par défaut :
        // on fixe la couleur de texte de base pour tout l'arbre.
        CompositionLocalProvider(LocalContentColor provides MyTvColors.OnBackground, content = content)
    }
}
