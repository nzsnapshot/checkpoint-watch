package nz.personal.checkpointwatch.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** OpenType tabular figures: times and counts keep their column as the digits change. */
const val TABULAR_FIGURES: String = "tnum"

/** The same [TextStyle] with fixed-width digits, for times, counts and anything that ticks. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = TABULAR_FIGURES)

private val Default = Typography()

/**
 * One hierarchy, three sizes on a card: the road name is the hero (title), the suburb is a small
 * tracked label above it, and everything else is body. The system sans is used throughout — no
 * bundled font, so the owner's own font settings and scaling apply unchanged.
 */
val AppTypography: Typography = Default.copy(
    // The collapsing top bar's large title.
    headlineMedium = Default.headlineMedium.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    ),
    // The big count on a summary tile.
    headlineSmall = Default.headlineSmall.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    // The hero of a report card: the road name.
    titleMedium = Default.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = Default.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    // Details and meta rows alike: a card keeps to three sizes, and this is its body size.
    // Tabular figures cost nothing on prose and keep "Reported 9:30 pm · 2 h ago" from shuffling.
    bodyMedium = Default.bodyMedium.copy(
        lineHeight = 21.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    bodySmall = Default.bodySmall.copy(
        lineHeight = 18.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    labelLarge = Default.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    // The suburb, as a tracked small-caps-style label.
    labelSmall = Default.labelSmall.copy(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 1.2.sp,
    ),
)
