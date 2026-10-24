package com.basetool.bpextractor.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * DAS KARTELL / KRT brand tokens, mirrored from the design system's `tokens.css`: house orange on the
 * gray canvas → panel → inset surfaces, controls with a fill and a visible border, Lato-only type.
 * There is no light theme.
 */
object Krt {
    val Orange = Color(0xFFE77E23)
    val OrangeHover = Color(0xFFEEB64B)
    val OrangeDark = Color(0xFFC45C00)
    val White = Color(0xFFFFFFFF)
    val Black = Color(0xFF000000)

    /** Page background, page head and boxes set into a panel (`--surface-canvas`, `--surface-inset`). */
    val Canvas = Color(0xFF0F0F0F)

    /** Title bar, tab band, footer, table head and inset fields (`--surface-bar`). */
    val Bar = Color(0xFF1A1D21)

    /** Cards, HUD boxes, modals and toasts (`--surface-panel`). */
    val Panel = Color(0xFF282C30)

    /** Decorative hairlines and panel frames only, never a control border (`--color-gray-3`). */
    val Hairline = Color(0xFF36373B)

    /** Resting fill of every non-CTA control (`--control-bg`). */
    val Control = Color(0xFF373E43)

    /** Hover fill of controls and segments (`--control-bg-hover`). */
    val ControlHover = Color(0xFF434B51)

    /** Zebra fill of every second table row, the control fill at half opacity (`--table-stripe-bg`). */
    val TableStripe = Color(0x80373E43)

    /** Border of every control, the scrollbar thumb and idle status dots (`--control-border`). */
    val ControlBorder = Color(0xFF8F9399)

    /** Tint behind a selected segment or option (`--control-selected-bg`). */
    val ControlSelected = Color(0x42E77E23)

    /** Fill of a count badge next to a title (`--color-count-badge`). */
    val CountBadge = Color(0xFF2F394C)

    /** Body text, labels and table text (`--color-gray-1`). */
    val Gray1 = Color(0xFFD1D2D3)

    /** Hints, placeholders, units and inactive tabs (`--color-gray-2-text`). */
    val TextMuted = Color(0xFFA2A5A9)

    val Danger = Color(0xFFA3000A)
    val DangerHover = Color(0xFFD41A25)
    val DangerText = Color(0xFFFF7A70)
    val Success = Color(0xFF239E33)
    val SuccessHover = Color(0xFF2EBC3D)
    val SuccessText = Color(0xFF4CCB5C)
    val Warning = Color(0xFFFFD23F)
    val Info = Color(0xFF355DDC)
    val InfoText = Color(0xFF8FB2F5)
}

val Lato = FontFamily(
    Font("fonts/Lato-Light.ttf", FontWeight.Light),
    Font("fonts/Lato-Regular.ttf", FontWeight.Normal),
    Font("fonts/Lato-Bold.ttf", FontWeight.Bold),
)

/** The 4 dp radius of controls and surfaces (`--radius-sm`). */
val KrtShape = RoundedCornerShape(4.dp)

/** The 2 dp radius of a checkbox and a switch knob. */
val KrtSmallShape = RoundedCornerShape(2.dp)

private val KrtColorScheme = darkColorScheme(
    primary = Krt.Orange,
    onPrimary = Krt.Black,
    primaryContainer = Krt.OrangeDark,
    onPrimaryContainer = Krt.White,
    secondary = Krt.OrangeDark,
    onSecondary = Krt.White,
    secondaryContainer = Krt.Control,
    onSecondaryContainer = Krt.Gray1,
    tertiary = Krt.Warning,
    onTertiary = Krt.Black,
    background = Krt.Canvas,
    onBackground = Krt.Gray1,
    surface = Krt.Panel,
    onSurface = Krt.Gray1,
    surfaceVariant = Krt.Bar,
    onSurfaceVariant = Krt.Gray1,
    outline = Krt.ControlBorder,
    outlineVariant = Krt.Hairline,
    error = Krt.Danger,
    onError = Krt.White,
    errorContainer = Krt.Danger,
    onErrorContainer = Krt.White,
    scrim = Krt.Black,
)

private val KrtTypography = Typography(
    headlineLarge = TextStyle(fontFamily = Lato, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = 0.05.em),
    headlineMedium = TextStyle(fontFamily = Lato, fontWeight = FontWeight.Bold, fontSize = 19.sp, letterSpacing = 0.05.em),
    headlineSmall = TextStyle(fontFamily = Lato, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 0.05.em),
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
        shapes = Shapes(extraSmall = KrtShape, small = KrtShape, medium = KrtShape, large = KrtShape, extraLarge = KrtShape),
    ) {
        CompositionLocalProvider(LocalScrollbarStyle provides KrtScrollbarStyle, content = content)
    }
}
