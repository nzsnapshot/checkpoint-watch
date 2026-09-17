package nz.personal.checkpointwatch.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The app's palette, written out by hand.
 *
 * Dark is the primary identity — a "night drive" scheme of near-black blues with one warm amber
 * accent, because most use is at night before setting off. Light is a matching daylight version of
 * the same scheme, not a different design.
 *
 * Every value is fixed: there is deliberately no dynamic (wallpaper) colour, so the app looks the
 * same on every phone and no stock Material purple can leak into an unfilled role.
 *
 * Contrast of the muted roles against the surfaces they sit on was measured (WCAG 2.1 relative
 * luminance) and every text pair clears AA 4.5:1; the notes below record the tight ones.
 */

// --- Dark ---------------------------------------------------------------------------------------

val DarkBackground = Color(0xFF0B1220)
val DarkSurface = Color(0xFF121B2E)
val DarkSurfaceVariant = Color(0xFF1F2A46)
val DarkSurfaceContainerLowest = Color(0xFF080E1B)
val DarkSurfaceContainerLow = Color(0xFF141D32)
val DarkSurfaceContainer = Color(0xFF1A2540)
val DarkSurfaceContainerHigh = Color(0xFF22304F)

/** 4.69:1 against [DarkOnSurfaceVariant]; a lighter step here would drop under AA. */
val DarkSurfaceContainerHighest = Color(0xFF263554)
val DarkSurfaceDim = Color(0xFF080E1B)
val DarkSurfaceBright = Color(0xFF2A3655)
val DarkOnSurface = Color(0xFFE8EDF7)
val DarkOnSurfaceVariant = Color(0xFF93A1BC)
val DarkOutline = Color(0xFF2A3655)
val DarkOutlineVariant = Color(0xFF202B45)

/**
 * The outline of an unselected chip. `outline` is a divider colour — 1.57:1 against the dark
 * background — and a chip's border is the whole of its shape, which WCAG asks to reach 3:1 like any
 * other control boundary. This measures 3.51:1 on the background and 3.23:1 on a card.
 */
val DarkChipOutline = Color(0xFF5A6B90)

val DarkPrimary = Color(0xFFFFB020)
val DarkOnPrimary = Color(0xFF1A1200)
val DarkPrimaryContainer = Color(0xFF4A3204)
val DarkOnPrimaryContainer = Color(0xFFFFDFA8)
val DarkSecondary = Color(0xFFF0C67A)
val DarkOnSecondary = Color(0xFF3A2600)

/** Amber over [DarkSurface] at low alpha: the selected state of a filter chip. */
val DarkSecondaryContainer = Color(0xFF463C2B)
val DarkOnSecondaryContainer = Color(0xFFFFD79A)
val DarkTertiary = Color(0xFF5CADFF)
val DarkOnTertiary = Color(0xFF002944)
val DarkTertiaryContainer = Color(0xFF123A5E)
val DarkOnTertiaryContainer = Color(0xFFCCE4FF)
val DarkError = Color(0xFFFF6B6F)
val DarkOnError = Color(0xFF1F0002)
val DarkErrorContainer = Color(0xFF4A1D20)
val DarkOnErrorContainer = Color(0xFFFFD9DA)
val DarkInverseSurface = Color(0xFFE8EDF7)
val DarkInverseOnSurface = Color(0xFF121B2E)
val DarkInversePrimary = Color(0xFF9A5B00)

// --- Light --------------------------------------------------------------------------------------

val LightBackground = Color(0xFFF6F7FB)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFE8ECF5)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF4F6FB)
val LightSurfaceContainer = Color(0xFFEEF1F8)
val LightSurfaceContainerHigh = Color(0xFFE6EBF4)

/** 4.60:1 against [LightOnSurfaceVariant]; a darker step here would drop under AA. */
val LightSurfaceContainerHighest = Color(0xFFE1E7F2)
val LightSurfaceDim = Color(0xFFE3E7F0)
val LightSurfaceBright = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF0B1220)
val LightOnSurfaceVariant = Color(0xFF5B677D)
val LightOutline = Color(0xFFD5DBE8)
val LightOutlineVariant = Color(0xFFE2E7F1)

/** As [DarkChipOutline]: `outline` is 1.29:1 here, this is 3.37:1 on the background, 3.61:1 on a card. */
val LightChipOutline = Color(0xFF7C879E)

val LightPrimary = Color(0xFF9A5B00)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFFFE7C2)
val LightOnPrimaryContainer = Color(0xFF3D2400)
val LightSecondary = Color(0xFF7A4A08)
val LightOnSecondary = Color(0xFFFFFFFF)

/** Amber over white at low alpha: the selected state of a filter chip. */
val LightSecondaryContainer = Color(0xFFFFE7C2)
val LightOnSecondaryContainer = Color(0xFF3D2400)
val LightTertiary = Color(0xFF1560B5)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFDEE9F5)
val LightOnTertiaryContainer = Color(0xFF06305C)
val LightError = Color(0xFFC8202D)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFDE3E5)
val LightOnErrorContainer = Color(0xFF5C0A11)
val LightInverseSurface = Color(0xFF121B2E)
val LightInverseOnSurface = Color(0xFFF6F7FB)
val LightInversePrimary = Color(0xFFFFB020)

// --- Report types -------------------------------------------------------------------------------

/**
 * One colour per report type, in both themes. Colour is never the only signal — an icon and a
 * label go with it everywhere — but each of these still clears AA as text on `surface` and
 * `background`, and 3:1 as an icon on its own tinted badge.
 */
val DarkCheckpoint = Color(0xFFFF6B6F)
val DarkPolice = Color(0xFF5CADFF)
val DarkCrash = Color(0xFFFFA24D)
val DarkCamera = Color(0xFFBE9CFA)
val DarkOther = Color(0xFF93A1BC)

val LightCheckpoint = Color(0xFFC8202D)
val LightPolice = Color(0xFF1560B5)
val LightCrash = Color(0xFFB35300)
val LightCamera = Color(0xFF6B41C4)
val LightOther = Color(0xFF5B677D)
