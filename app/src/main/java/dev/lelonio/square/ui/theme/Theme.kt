package dev.lelonio.square.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalMotionDurationScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.lelonio.square.ui.design.SquareUiTokens

@Composable
fun SquareTheme(
    seed: Color? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val base = seed ?: DefaultAccent
    val accent = if (darkTheme) base.liftFor(DarkBase) else base.deepenFor(LightBase)
    val motionScale = LocalMotionDurationScale.current
    val animatedAccent by animateColorAsState(
        targetValue = accent,
        animationSpec = if (motionScale.scaleFactor == 0f) snap() else tween(
            (600 * motionScale.scaleFactor).toInt().coerceAtLeast(1),
        ),
        label = "accent",
    )
    val scheme = remember(animatedAccent) {
        darkColorScheme(
            primary = animatedAccent,
            onPrimary = Color(0xFF0B0D10),
            primaryContainer = GlassFill,
            onPrimaryContainer = Ink,
            secondary = InkDim,
            background = Color.Transparent,
            onBackground = Ink,
            surface = GlassFill,
            onSurface = Ink,
            surfaceVariant = GlassFillStrong,
            onSurfaceVariant = InkDim,
            outlineVariant = Color.White.copy(alpha = 0.16f),
        )
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as android.app.Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(colorScheme = scheme, typography = SpotTypography, shapes = SquareShapes, content = content)
}

private val DarkBase = Color(0xFF0D0E11)
private val LightBase = Color(0xFFF1F2F6)
private val GlassFill = Color.White.copy(alpha = 0.10f)
private val GlassFillStrong = Color.White.copy(alpha = 0.16f)
val Ink = Color(0xFFF7F8FA)
val InkDim = Color(0xFFF7F8FA).copy(alpha = 0.66f)
private val DefaultAccent = Color(0xFF7C5CE6)

private val SquareShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(SquareUiTokens.RadiusSmall),
    small = androidx.compose.foundation.shape.RoundedCornerShape(SquareUiTokens.RadiusSmall),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(SquareUiTokens.RadiusMedium),
    large = androidx.compose.foundation.shape.RoundedCornerShape(SquareUiTokens.RadiusLarge),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(SquareUiTokens.RadiusLarge),
)

private fun Color.liftFor(background: Color): Color {
    val floor = background.luminance() + 0.16f
    if (luminance() >= floor) return this
    val amount = ((floor - luminance()) * 2f).coerceIn(0f, 1f)
    return Color(red + (1f - red) * amount, green + (1f - green) * amount, blue + (1f - blue) * amount)
}

private fun Color.deepenFor(background: Color): Color {
    val ceiling = background.luminance() - 0.42f
    if (luminance() <= ceiling) return this
    val amount = ((luminance() - ceiling) * 1.1f).coerceIn(0f, 0.8f)
    return Color(red * (1f - amount), green * (1f - amount), blue * (1f - amount))
}

fun Modifier.softShadow(
    shape: Shape,
    elevation: Dp = 18.dp,
    ambient: Float = 0.10f,
    spot: Float = 0.13f,
): Modifier = shadow(elevation = elevation, shape = shape, clip = false, ambientColor = Color.Black.copy(alpha = ambient), spotColor = Color.Black.copy(alpha = spot))

private val SpotTypography = Typography(
    displayLarge = TextStyle(fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.2).sp),
    displayMedium = TextStyle(fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.9).sp),
    headlineLarge = TextStyle(fontSize = 27.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontSize = 15.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.1).sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 10.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
)
