package com.memeget.nativeapp

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object MemegetColors {
    val Volt = Color(0xFFCCFF00)
    val VoltContainer = Color(0xFF233000)
    val VoltMuted = Color(0xFF99CC00)

    val Background = Color(0xFF0E1116)
    val Surface = Color(0xFF161B22)
    val SurfaceVariant = Color(0xFF21262D)
    val SurfaceBright = Color(0xFF2D333B)

    val Border = Color(0xFF30363D)
    val BorderSubtle = Color(0xFF21262D)

    val TextPrimary = Color(0xFFF0F6FC)
    val TextSecondary = Color(0xFF8B949E)
    val TextMuted = Color(0xFF6E7681)

    val Danger = Color(0xFFFF5252)
    val DangerContainer = Color(0xFF3B1219)
    val Success = Color(0xFF3FB950)
    val SuccessContainer = Color(0xFF132D1B)
    val Warning = Color(0xFFD29922)
    val WarningContainer = Color(0xFF342800)
    val Blue = Color(0xFF58A6FF)
    val BlueContainer = Color(0xFF0D2D4D)
}

private val MemegetDarkColorScheme: ColorScheme = darkColorScheme(
    primary = MemegetColors.Volt,
    onPrimary = Color.Black,
    primaryContainer = MemegetColors.VoltContainer,
    onPrimaryContainer = MemegetColors.Volt,
    secondary = MemegetColors.VoltMuted,
    onSecondary = Color.Black,
    secondaryContainer = MemegetColors.SurfaceBright,
    onSecondaryContainer = MemegetColors.TextPrimary,
    tertiary = MemegetColors.Blue,
    onTertiary = Color.Black,
    tertiaryContainer = MemegetColors.BlueContainer,
    onTertiaryContainer = MemegetColors.Blue,
    background = MemegetColors.Background,
    onBackground = MemegetColors.TextPrimary,
    surface = MemegetColors.Surface,
    onSurface = MemegetColors.TextPrimary,
    surfaceVariant = MemegetColors.SurfaceVariant,
    onSurfaceVariant = MemegetColors.TextSecondary,
    outline = MemegetColors.Border,
    outlineVariant = MemegetColors.BorderSubtle,
    error = MemegetColors.Danger,
    onError = Color.Black,
    errorContainer = MemegetColors.DangerContainer,
    onErrorContainer = MemegetColors.Danger,
)

private val MemegetShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private val MemegetTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp,
        color = MemegetColors.TextPrimary,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp,
        color = MemegetColors.TextPrimary,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        color = MemegetColors.TextPrimary,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        color = MemegetColors.TextPrimary,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = MemegetColors.TextPrimary,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = MemegetColors.TextSecondary,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        color = MemegetColors.TextMuted,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.3.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 9.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.4.sp,
    ),
)

@Composable
internal fun MemegetTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = MemegetDarkColorScheme,
        shapes = MemegetShapes,
        typography = MemegetTypography,
        content = content,
    )
}
