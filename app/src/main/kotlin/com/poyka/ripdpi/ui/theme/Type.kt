package com.poyka.ripdpi.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.poyka.ripdpi.R

val GeistSansFamily =
    FontFamily(
        Font(R.font.geist_sans_regular, FontWeight.Normal),
        Font(R.font.geist_sans_medium, FontWeight.Medium),
        Font(R.font.geist_sans_bold, FontWeight.Bold),
    )

val GeistMonoFamily =
    FontFamily(
        Font(R.font.geist_mono_regular, FontWeight.Normal),
        Font(R.font.geist_mono_medium, FontWeight.Medium),
        Font(R.font.geist_mono_bold, FontWeight.Bold),
    )

val GeistPixelCircleFamily =
    FontFamily(
        Font(R.font.geist_pixel_circle, FontWeight.Normal),
    )

private fun sansStyle(
    fontSize: Int,
    lineHeight: Int,
    fontWeight: FontWeight,
    letterSpacing: Float = 0f,
) = TextStyle(
    fontFamily = GeistSansFamily,
    fontWeight = fontWeight,
    fontSize = fontSize.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

private fun monoStyle(
    fontSize: Int,
    lineHeight: Int,
    fontWeight: FontWeight = FontWeight.Normal,
    letterSpacing: Float = 0f,
) = TextStyle(
    fontFamily = GeistMonoFamily,
    fontWeight = fontWeight,
    fontSize = fontSize.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

private fun pixelStyle(
    fontSize: Int,
    lineHeight: Int,
    letterSpacing: Float = 0f,
) = TextStyle(
    fontFamily = GeistPixelCircleFamily,
    fontWeight = FontWeight.Normal,
    fontSize = fontSize.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

@Immutable
data class RipDpiTextStyles(
    val screenTitle: TextStyle,
    val appBarTitle: TextStyle,
    val sheetTitle: TextStyle,
    val sectionTitle: TextStyle,
    val introAction: TextStyle,
    val introTitle: TextStyle,
    val introBody: TextStyle,
    val body: TextStyle,
    val bodyEmphasis: TextStyle,
    val secondaryBody: TextStyle,
    val caption: TextStyle,
    val smallLabel: TextStyle,
    val button: TextStyle,
    val navLabel: TextStyle,
    val monoValue: TextStyle,
    val monoConfig: TextStyle,
    val monoInline: TextStyle,
    val monoLog: TextStyle,
    val monoSmall: TextStyle,
    val brandMark: TextStyle,
    val brandStatus: TextStyle,
)

val RipDpiTypeScale =
    RipDpiTextStyles(
        screenTitle = sansStyle(fontSize = 22, lineHeight = 28, fontWeight = FontWeight.Medium),
        appBarTitle = sansStyle(fontSize = 20, lineHeight = 28, fontWeight = FontWeight.Medium),
        sheetTitle = sansStyle(fontSize = 18, lineHeight = 24, fontWeight = FontWeight.Medium),
        sectionTitle =
            sansStyle(
                fontSize = 13,
                lineHeight = 18,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.72f,
            ),
        introAction = sansStyle(fontSize = 14, lineHeight = 21, fontWeight = FontWeight.Normal),
        introTitle = sansStyle(fontSize = 22, lineHeight = 30, fontWeight = FontWeight.Medium),
        introBody = sansStyle(fontSize = 15, lineHeight = 22, fontWeight = FontWeight.Normal),
        body = sansStyle(fontSize = 14, lineHeight = 20, fontWeight = FontWeight.Normal),
        bodyEmphasis = sansStyle(fontSize = 14, lineHeight = 20, fontWeight = FontWeight.Medium),
        secondaryBody = sansStyle(fontSize = 14, lineHeight = 20, fontWeight = FontWeight.Normal),
        caption = sansStyle(fontSize = 12, lineHeight = 16, fontWeight = FontWeight.Normal),
        smallLabel = sansStyle(fontSize = 12, lineHeight = 16, fontWeight = FontWeight.Medium),
        button = sansStyle(fontSize = 15, lineHeight = 20, fontWeight = FontWeight.Medium),
        navLabel = sansStyle(fontSize = 12, lineHeight = 16, fontWeight = FontWeight.Medium),
        monoValue = monoStyle(fontSize = 14, lineHeight = 20),
        monoConfig = monoStyle(fontSize = 14, lineHeight = 20),
        monoInline = monoStyle(fontSize = 13, lineHeight = 20),
        monoLog = monoStyle(fontSize = 12, lineHeight = 20),
        monoSmall = monoStyle(fontSize = 12, lineHeight = 16),
        brandMark = pixelStyle(fontSize = 32, lineHeight = 48, letterSpacing = 0.8f),
        brandStatus = monoStyle(fontSize = 13, lineHeight = 18, fontWeight = FontWeight.Medium),
    )

val RipDpiTypography =
    Typography(
        headlineSmall = RipDpiTypeScale.screenTitle,
        titleLarge = RipDpiTypeScale.appBarTitle,
        titleMedium = RipDpiTypeScale.sheetTitle,
        titleSmall = RipDpiTypeScale.sectionTitle,
        bodyLarge = RipDpiTypeScale.body,
        bodyMedium = RipDpiTypeScale.secondaryBody,
        bodySmall = RipDpiTypeScale.caption,
        labelLarge = RipDpiTypeScale.button,
        labelMedium = RipDpiTypeScale.smallLabel,
        labelSmall = RipDpiTypeScale.navLabel,
    )

internal val LocalRipDpiTextStyles = staticCompositionLocalOf { RipDpiTypeScale }
