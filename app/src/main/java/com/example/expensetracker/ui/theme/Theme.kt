package com.example.expensetracker.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DailySpendColorScheme = lightColorScheme(
    primary = EmeraldPrimary,
    onPrimary = EmeraldOnPrimary,
    primaryContainer = EmeraldContainer,
    onPrimaryContainer = EmeraldOnContainer,
    secondary = EmeraldOnContainer,
    secondaryContainer = EmeraldContainer,
    onSecondaryContainer = EmeraldOnContainer,
    background = SurfaceTint,
    onBackground = InkPrimary,
    surface = SurfaceBright,
    onSurface = InkPrimary,
    surfaceVariant = EmeraldContainer.copy(alpha = 0.35f),
    onSurfaceVariant = InkSecondary,
    outline = OutlineSoft,
    outlineVariant = OutlineSoft,
    error = DangerAccent,
    onError = Color.White,
)

private val DailySpendShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Light scheme on purpose: the lab module writes visible artefacts, so a dark-mode variant would add
 * nothing and would complicate screenshot evidence.
 */
@Composable
fun DailySpendTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DailySpendColorScheme,
        typography = Typography(),
        shapes = DailySpendShapes,
        content = content,
    )
}

/** Category colour used by the dashboard breakdown and history chips. */
fun categoryColor(key: String): Color = when (key.uppercase()) {
    "FOOD" -> CategoryFood
    "TRANSPORT" -> CategoryTransport
    "SHOPPING" -> CategoryShopping
    "EDUCATION" -> CategoryEducation
    else -> CategoryOther
}
