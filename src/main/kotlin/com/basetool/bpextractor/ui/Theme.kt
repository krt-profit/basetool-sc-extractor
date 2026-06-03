package com.basetool.bpextractor.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * DAS KARTELL / KRT brand tokens, ported from the `das-kartell-design` skill
 * (`colors_and_type.css`). Dark sci-fi "HUD": one hero orange on black, square
 * corners, Ethnocentric display + Lato body. There is no light theme.
 */
object Krt {
    // House colors (Corporate Design Manual p.12)
    val Orange = Color(0xFFE77E23)       // HAUSFARBE — action + identity only
    val OrangeHover = Color(0xFFEEB64B)  // ZIERFARBE HELL — orange hover
    val OrangeDark = Color(0xFFC45C00)   // ZIERFARBE DUNKEL — deep accent
    val White = Color(0xFFFFFFFF)
    val Black = Color(0xFF000000)        // page background

    // Grayscale (Manual p.13), light → dark
    val Gray1 = Color(0xFFD2D2D2)        // primary body text on dark
    val Gray2 = Color(0xFF646464)        // muted text, placeholders, disabled
    val Gray3 = Color(0xFF282828)        // hairline borders, hover-row fill
    val Gray4 = Color(0xFF141414)        // darkest; standard surface
    val SurfaceInput = Color(0xFF1C1C1C) // input / table-head fill (half-step above surface)

    // Semantic (reuse Bereichsfarben values by appearance)
    val Danger = Color(0xFFA3000A)
    val DangerHover = Color(0xFFD41A25)
    val Success = Color(0xFF239E33)
    val Warning = Color(0xFFFFD23F)
    val Info = Color(0xFF355DDC)
}

// Two faces only — Ethnocentric (display, UPPERCASE) + Lato (body). Loaded from
// the classpath (src/main/resources/fonts), so they work in the packaged app too.
val Ethnocentric = FontFamily(Font("fonts/Ethnocentric_Rg.otf", FontWeight.Normal))
val Lato = FontFamily(
    Font("fonts/Lato-Light.ttf", FontWeight.Light),
    Font("fonts/Lato-Regular.ttf", FontWeight.Normal),
    Font("fonts/Lato-Bold.ttf", FontWeight.Bold),
)

private val Square = RoundedCornerShape(0.dp)

private val KrtColorScheme = darkColorScheme(
    primary = Krt.Orange,
    onPrimary = Krt.Black,
    primaryContainer = Krt.OrangeDark,
    onPrimaryContainer = Krt.White,
    secondary = Krt.OrangeDark,
    onSecondary = Krt.White,
    secondaryContainer = Krt.Gray3,
    onSecondaryContainer = Krt.Gray1,
    tertiary = Krt.Warning,
    onTertiary = Krt.Black,
    background = Krt.Black,
    onBackground = Krt.Gray1,
    surface = Krt.Gray4,
    onSurface = Krt.Gray1,
    surfaceVariant = Krt.SurfaceInput,
    onSurfaceVariant = Krt.Gray1,
    outline = Krt.Gray3,
    outlineVariant = Krt.Gray3,
    error = Krt.Danger,
    onError = Krt.White,
    errorContainer = Krt.Danger,
    onErrorContainer = Krt.White,
    scrim = Krt.Black,
)

// Headings: Ethnocentric, +0.05em tracking (always rendered UPPERCASE by callers).
// Body/labels: Lato — Light 300 for prose, Bold 700 for labels/buttons.
private val KrtTypography = Typography(
    headlineLarge = TextStyle(fontFamily = Ethnocentric, fontWeight = FontWeight.Normal, fontSize = 30.sp, letterSpacing = 0.05.em),
    headlineMedium = TextStyle(fontFamily = Ethnocentric, fontWeight = FontWeight.Normal, fontSize = 23.sp, letterSpacing = 0.05.em),
    headlineSmall = TextStyle(fontFamily = Ethnocentric, fontWeight = FontWeight.Normal, fontSize = 15.sp, letterSpacing = 0.08.em),
    titleMedium = TextStyle(fontFamily = Lato, fontWeight = FontWeight.Bold, fontSize = 16.sp),
    labelLarge = TextStyle(fontFamily = Lato, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.03.em),
    labelMedium = TextStyle(fontFamily = Lato, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.06.em),
    bodyLarge = TextStyle(fontFamily = Lato, fontWeight = FontWeight.Light, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Lato, fontWeight = FontWeight.Light, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Lato, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 20.sp),
)

/** Monospace-feel readout style: Lato with tabular figures so counts/IDs align. */
val KrtDataStyle = TextStyle(
    fontFamily = Lato,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 20.sp,
    fontFeatureSettings = "tnum",
)

@Composable
fun KrtTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KrtColorScheme,
        typography = KrtTypography,
        shapes = Shapes(extraSmall = Square, small = Square, medium = Square, large = Square, extraLarge = Square),
        content = content,
    )
}
