package com.boneink.picapica.ui

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Radix neutral surfaces and crimson controls keep the artwork in focus. */
private val Light = lightColorScheme(
    primary = Color(0xFFCB1D63),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFEE5F0),
    onPrimaryContainer = Color(0xFF621639),
    secondary = Color(0xFF0D74CE),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6F4FE),
    onSecondaryContainer = Color(0xFF113264),
    tertiary = Color(0xFF0D74CE),
    onTertiary = Color.White,
    background = Color(0xFFFCFCFD),
    onBackground = Color(0xFF1C2024),
    surface = Color.White,
    onSurface = Color(0xFF1C2024),
    surfaceVariant = Color(0xFFF0F0F3),
    onSurfaceVariant = Color(0xFF60646C),
    outline = Color(0xFFB9BBC6),
    outlineVariant = Color(0xFFE0E1E6),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF9F9FB),
    surfaceContainerHigh = Color(0xFFF0F0F3),
    surfaceContainerHighest = Color(0xFFE8E8EC),
    inverseSurface = Color(0xFF1C2024),
    inverseOnSurface = Color(0xFFFCFCFD),
    error = Color(0xFFCE2C31),
    onError = Color.White,
    errorContainer = Color(0xFFFEEBEC),
    onErrorContainer = Color(0xFF641723),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFF92AD),
    onPrimary = Color(0xFF3E1023),
    primaryContainer = Color(0xFF381525),
    onPrimaryContainer = Color(0xFFFFD1DC),
    secondary = Color(0xFF70B8FF),
    onSecondary = Color(0xFF10243E),
    secondaryContainer = Color(0xFF102A4C),
    onSecondaryContainer = Color(0xFFC2E6FF),
    tertiary = Color(0xFF70B8FF),
    onTertiary = Color(0xFF10243E),
    background = Color(0xFF111113),
    onBackground = Color(0xFFEDEEF0),
    surface = Color(0xFF18191B),
    onSurface = Color(0xFFEDEEF0),
    surfaceVariant = Color(0xFF212225),
    onSurfaceVariant = Color(0xFFB0B4BA),
    outline = Color(0xFF60646C),
    outlineVariant = Color(0xFF363A3F),
    surfaceContainerLowest = Color(0xFF111113),
    surfaceContainerLow = Color(0xFF18191B),
    surfaceContainer = Color(0xFF1E1F22),
    surfaceContainerHigh = Color(0xFF272A2D),
    surfaceContainerHighest = Color(0xFF2E3135),
    inverseSurface = Color(0xFFEDEEF0),
    inverseOnSurface = Color(0xFF1C2024),
    error = Color(0xFFFF9592),
    onError = Color(0xFF4E1519),
    errorContainer = Color(0xFF3B181B),
    onErrorContainer = Color(0xFFFFDAD8),
)

/** Reader and image viewer controls stay readable on their dark canvas. */
val PicaAccent = Color(0xFFFF92AD)

private val sans = FontFamily.SansSerif
private val PicaTypography = Typography(
    displayLarge = TextStyle(fontFamily = sans, fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontFamily = sans, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontFamily = sans, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontFamily = sans, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontFamily = sans, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontFamily = sans, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontFamily = sans, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = sans, fontSize = 15.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = sans, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = sans, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = sans, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontFamily = sans, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = sans, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
)

private val PicaShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun PicaTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val activity = LocalActivity.current as? ComponentActivity
    val view = LocalView.current
    DisposableEffect(view) {
        if (Build.VERSION.SDK_INT >= 35) {
            val previousRate = view.requestedFrameRate
            view.requestedFrameRate = android.view.View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
            onDispose { view.requestedFrameRate = previousRate }
        } else onDispose { }
    }
    DisposableEffect(activity, darkTheme) {
        activity?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { darkTheme },
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { darkTheme },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) activity?.window?.isNavigationBarContrastEnforced = false
        onDispose { }
    }
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, shapes = PicaShapes, typography = PicaTypography, content = content)
}

object PicaDepth {
    val card = 0.dp
    val panel = 0.dp
    val chrome = 0.dp
}

@Composable
fun panelBorder() = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant)

@Composable
fun PicaPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp, shadowElevation = 0.dp, border = panelBorder(), content = content)
}

@Composable
fun HairlineRule(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.outlineVariant, thickness: Dp = .5.dp) {
    Box(modifier.fillMaxWidth().height(thickness).background(color))
}
