package app.megaglass.android.ui.theme

import android.content.res.Resources
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import app.megaglass.android.core.storage.AppSettings
import app.megaglass.android.core.storage.ThemeMode

/** MegaGlass red, used when dynamic colour is off. */
private val LightBrand = lightColorScheme(
    primary = Color(0xFFBB1614),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD5),
    onPrimaryContainer = Color(0xFF410001),
    secondary = Color(0xFF775651),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDAD5),
    onSecondaryContainer = Color(0xFF2C1512),
    tertiary = Color(0xFF705C2E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFBDFA6),
    onTertiaryContainer = Color(0xFF251A00),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F6),
    onBackground = Color(0xFF231918),
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF231918),
    surfaceVariant = Color(0xFFF5DDDA),
    onSurfaceVariant = Color(0xFF534341),
    outline = Color(0xFF857371),
    outlineVariant = Color(0xFFD8C2BF),
    inverseSurface = Color(0xFF392E2C),
    inverseOnSurface = Color(0xFFFFEDEA),
    inversePrimary = Color(0xFFFFB4AA),
    surfaceDim = Color(0xFFE8D6D3),
    surfaceBright = Color(0xFFFFF8F6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF0EE),
    surfaceContainer = Color(0xFFFCEAE7),
    surfaceContainerHigh = Color(0xFFF6E4E1),
    surfaceContainerHighest = Color(0xFFF1DEDC),
)

private val DarkBrand = darkColorScheme(
    primary = Color(0xFFFFB4AA),
    onPrimary = Color(0xFF690002),
    primaryContainer = Color(0xFF93000A),
    onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = Color(0xFFE7BDB6),
    onSecondary = Color(0xFF442925),
    secondaryContainer = Color(0xFF5D3F3B),
    onSecondaryContainer = Color(0xFFFFDAD5),
    tertiary = Color(0xFFDEC48C),
    onTertiary = Color(0xFF3E2E04),
    tertiaryContainer = Color(0xFF56441A),
    onTertiaryContainer = Color(0xFFFBDFA6),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1A1110),
    onBackground = Color(0xFFF1DEDC),
    surface = Color(0xFF1A1110),
    onSurface = Color(0xFFF1DEDC),
    surfaceVariant = Color(0xFF534341),
    onSurfaceVariant = Color(0xFFD8C2BF),
    outline = Color(0xFFA08C8A),
    outlineVariant = Color(0xFF534341),
    inverseSurface = Color(0xFFF1DEDC),
    inverseOnSurface = Color(0xFF392E2C),
    inversePrimary = Color(0xFFBB1614),
    surfaceDim = Color(0xFF1A1110),
    surfaceBright = Color(0xFF423735),
    surfaceContainerLowest = Color(0xFF140C0B),
    surfaceContainerLow = Color(0xFF231918),
    surfaceContainer = Color(0xFF271D1C),
    surfaceContainerHigh = Color(0xFF322826),
    surfaceContainerHighest = Color(0xFF3D3231),
)

/** Extra colours that Material does not define. */
@Immutable
data class ExtraColors(
    val success: Color,
    val warning: Color,
    val folder: Color,
    val image: Color,
    val video: Color,
    val audio: Color,
    val pdf: Color,
    val document: Color,
    val spreadsheet: Color,
    val presentation: Color,
    val archive: Color,
    val code: Color,
    val other: Color,
)

private val LightExtra = ExtraColors(
    success = Color(0xFF1B7F3B), warning = Color(0xFFB26A00), folder = Color(0xFFE2A41E),
    image = Color(0xFF2E7D32), video = Color(0xFFC62828), audio = Color(0xFF6A1B9A), pdf = Color(0xFFD32F2F),
    document = Color(0xFF1565C0), spreadsheet = Color(0xFF2E7D32), presentation = Color(0xFFEF6C00),
    archive = Color(0xFF6D4C41), code = Color(0xFF455A64), other = Color(0xFF757575),
)

private val DarkExtra = ExtraColors(
    success = Color(0xFF7DDB94), warning = Color(0xFFFFB95C), folder = Color(0xFFF7C35A),
    image = Color(0xFF81C784), video = Color(0xFFEF9A9A), audio = Color(0xFFCE93D8), pdf = Color(0xFFFF8A80),
    document = Color(0xFF90CAF9), spreadsheet = Color(0xFFA5D6A7), presentation = Color(0xFFFFCC80),
    archive = Color(0xFFBCAAA4), code = Color(0xFFB0BEC5), other = Color(0xFFBDBDBD),
)

val LocalExtraColors = staticCompositionLocalOf { LightExtra }

private fun ColorScheme.pureBlack() = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0B),
    surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF222222),
)

/** Dark or light for this mode; [systemDark] is the system setting. */
fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun isDarkTheme(settings: AppSettings): Boolean = settings.themeMode.isDark(isSystemInDarkTheme())

// The navigation bar scrims of the default enableEdgeToEdge() (internal in androidx.activity).
private val LightScrim = android.graphics.Color.argb(0xe6, 0xff, 0xff, 0xff)
private val DarkScrim = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)

/**
 * Edge to edge, with bar icons for the app theme and not only for the system theme
 * (MEGAGLASS-UX-007). After a configuration change (for example a system dark-mode change),
 * the activity runs the [dark] of its first call again, so that one must read the current settings.
 */
fun ComponentActivity.edgeToEdge(dark: (Resources) -> Boolean) = enableEdgeToEdge(
    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT, dark),
    navigationBarStyle = SystemBarStyle.auto(LightScrim, DarkScrim, dark),
)

@Composable
fun MegaGlassTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = isDarkTheme(settings)
    val context = LocalContext.current
    // A change of the app theme is not a configuration change: set the bar icons again.
    val activity = LocalActivity.current as? ComponentActivity
    DisposableEffect(activity, dark) {
        activity?.edgeToEdge { dark }
        onDispose {}
    }
    // The activity keeps a wallpaper-colour change (assetsPaths): the configuration key reads the dynamic colours again.
    val colors = remember(dark, settings.dynamicColor, settings.pureBlack, LocalConfiguration.current) {
        val base = when {
            settings.dynamicColor && dark -> dynamicDarkColorScheme(context)
            settings.dynamicColor -> dynamicLightColorScheme(context)
            dark -> DarkBrand
            else -> LightBrand
        }
        if (dark && settings.pureBlack) base.pureBlack() else base
    }
    CompositionLocalProvider(LocalExtraColors provides if (dark) DarkExtra else LightExtra) {
        MaterialExpressiveTheme(
            colorScheme = colors,
            motionScheme = MotionScheme.expressive(),
            shapes = Shapes(),
            typography = AppTypography,
            content = content,
        )
    }
}

object AppTheme {
    val extra: ExtraColors @Composable get() = LocalExtraColors.current
    val colors: ColorScheme @Composable get() = MaterialTheme.colorScheme
}
