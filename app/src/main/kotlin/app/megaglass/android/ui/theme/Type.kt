package app.megaglass.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight

/** System font (respects the device font), with heavier titles for the expressive style. */
val AppTypography: Typography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.SemiBold),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}
