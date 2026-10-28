package com.boneink.picapica.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.LocalActivity
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke

// Radix UI Colors: Crimson for actions, Slate for surfaces and text.
val PicaPink = Color(0xFFFF92AD)
private val Light = lightColorScheme(
    primary = Color(0xFFCB1D63), onPrimary = Color.White, primaryContainer = Color(0xFFFFE9F0), onPrimaryContainer = Color(0xFF621639),
    secondary = Color(0xFFCB1D63), onSecondary = Color.White, secondaryContainer = Color(0xFFFFE9F0), onSecondaryContainer = Color(0xFF621639),
    background = Color(0xFFF2F2F5), onBackground = Color(0xFF1C2024), surface = Color(0xFFFCFCFD), onSurface = Color(0xFF1C2024),
    surfaceVariant = Color(0xFFF0F0F3), onSurfaceVariant = Color(0xFF60646C), outline = Color(0xFFCDCED6), outlineVariant = Color(0xFFE0E1E6),
    surfaceContainer = Color(0xFFF0F0F3), surfaceContainerHigh = Color(0xFFE8E8EC), surfaceContainerLow = Color(0xFFFCFCFD),
    error = Color(0xFFCE2C31), onError = Color.White, errorContainer = Color(0xFFFFE5E5), onErrorContainer = Color(0xFF641723),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFFF92AD), onPrimary = Color(0xFF381525), primaryContainer = Color(0xFF381525), onPrimaryContainer = Color(0xFFFDD3E8),
    secondary = Color(0xFFFF92AD), onSecondary = Color(0xFF381525), secondaryContainer = Color(0xFF381525), onSecondaryContainer = Color(0xFFFDD3E8),
    background = Color(0xFF111113), onBackground = Color(0xFFEDEEF0), surface = Color(0xFF18191B), onSurface = Color(0xFFEDEEF0),
    surfaceVariant = Color(0xFF212225), onSurfaceVariant = Color(0xFFB0B4BA), outline = Color(0xFF43484E), outlineVariant = Color(0xFF2E3135),
    surfaceContainer = Color(0xFF212225), surfaceContainerHigh = Color(0xFF272A2D), surfaceContainerLow = Color(0xFF18191B),
    error = Color(0xFFFF9592), onError = Color(0xFF3B1219), errorContainer = Color(0xFF3B1219), onErrorContainer = Color(0xFFFFD1D9),
)
@Composable
fun PicaTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val activity = LocalActivity.current as? ComponentActivity
    DisposableEffect(activity, darkTheme) {
        activity?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { darkTheme },
            navigationBarStyle = SystemBarStyle.auto(0xFFFCFCFD.toInt(), 0xFF18191B.toInt()) { darkTheme },
        )
        onDispose { }
    }
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, shapes = Shapes(
        extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
        extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    ), typography = Typography(
        headlineLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
        headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 23.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
        labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    ), content = content)
}

/** Shared depth and edge treatment for content surfaces in both themes. */
object PicaDepth {
    val card = 6.dp
    val panel = 3.dp
    val chrome = 8.dp
}

@Composable
fun panelBorder() = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))

@Composable
fun PicaPanel(modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier, content: @Composable () -> Unit) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = PicaDepth.panel, border = panelBorder(), content = content)
}
