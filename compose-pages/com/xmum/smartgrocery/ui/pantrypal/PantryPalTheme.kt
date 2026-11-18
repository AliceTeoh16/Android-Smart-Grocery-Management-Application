package com.xmum.smartgrocery.ui.pantrypal

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object PantryColors {
    val Cream = Color(0xFFF9F8F3)
    val Orange = Color(0xFFC85A32)
    val Green = Color(0xFF315B40)
    val Ink = Color(0xFF24372B)
    val Muted = Color(0xFF738075)
    val Pastel = Color(0xFFE6EFDE)
    val Border = Color(0xFFDEE4D8)
    val Peach = Color(0xFFFAEBDD)
    val Pale = Color(0xFFF1F5EC)
}

private val PantryTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 39.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 29.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp),
)

/** Scope this theme to the new flow; it does not change the existing application's theme. */
@Composable
fun PantryPalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = PantryColors.Green,
            onPrimary = Color.White,
            secondary = PantryColors.Orange,
            onSecondary = Color.White,
            background = PantryColors.Cream,
            onBackground = PantryColors.Ink,
            surface = PantryColors.Cream,
            onSurface = PantryColors.Ink,
            onSurfaceVariant = PantryColors.Muted,
            outline = PantryColors.Border,
            error = Color(0xFFAD3327),
        ),
        typography = PantryTypography,
        content = content,
    )
}
