package com.poyka.ripdpi.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
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
    hyphens = Hyphens.Auto,
    lineBreak = LineBreak.Simple,
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
    val cardTitle: TextStyle,
    val sectionTitle: TextStyle,
    val introAction: TextStyle,
    val introTitle: TextStyle,
    val introBody: TextStyle,
    val screenTitleEmphasis: TextStyle,
    val body: TextStyle,
    val bodyEmphasis: TextStyle,
    val bodyEmphasisBold: TextStyle,
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

internal fun RipDpiTextStyles.withLocaleList(locales: LocaleList): RipDpiTextStyles =
    copy(
        screenTitle = screenTitle.copy(localeList = locales),
        appBarTitle = appBarTitle.copy(localeList = locales),
        sheetTitle = sheetTitle.copy(localeList = locales),
        cardTitle = cardTitle.copy(localeList = locales),
        sectionTitle = sectionTitle.copy(localeList = locales),
        introAction = introAction.copy(localeList = locales),
        introTitle = introTitle.copy(localeList = locales),
        introBody = introBody.copy(localeList = locales),
        screenTitleEmphasis = screenTitleEmphasis.copy(localeList = locales),
        body = body.copy(localeList = locales),
        bodyEmphasis = bodyEmphasis.copy(localeList = locales),
        bodyEmphasisBold = bodyEmphasisBold.copy(localeList = locales),
        secondaryBody = secondaryBody.copy(localeList = locales),
        caption = caption.copy(localeList = locales),
        smallLabel = smallLabel.copy(localeList = locales),
        button = button.copy(localeList = locales),
        navLabel = navLabel.copy(localeList = locales),
        monoValue = monoValue.copy(localeList = locales),
        monoConfig = monoConfig.copy(localeList = locales),
        monoInline = monoInline.copy(localeList = locales),
        monoLog = monoLog.copy(localeList = locales),
        monoSmall = monoSmall.copy(localeList = locales),
        brandMark = brandMark.copy(localeList = locales),
        brandStatus = brandStatus.copy(localeList = locales),
    )

val RipDpiTypeScale =
    RipDpiTextStyles(
        screenTitle = sansStyle(fontSize = 24, lineHeight = 32, fontWeight = FontWeight.Medium),
        appBarTitle = sansStyle(fontSize = 22, lineHeight = 28, fontWeight = FontWeight.Medium),
        sheetTitle = sansStyle(fontSize = 22, lineHeight = 28, fontWeight = FontWeight.Medium),
        cardTitle = sansStyle(fontSize = 18, lineHeight = 24, fontWeight = FontWeight.Medium),
        sectionTitle =
            sansStyle(
                fontSize = 14,
                lineHeight = 20,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.72f,
            ),
        introAction = sansStyle(fontSize = 14, lineHeight = 21, fontWeight = FontWeight.Normal),
        introTitle = sansStyle(fontSize = 24, lineHeight = 32, fontWeight = FontWeight.Medium),
        introBody = sansStyle(fontSize = 16, lineHeight = 24, fontWeight = FontWeight.Normal),
        screenTitleEmphasis = sansStyle(fontSize = 24, lineHeight = 32, fontWeight = FontWeight.Bold),
        body = sansStyle(fontSize = 16, lineHeight = 24, fontWeight = FontWeight.Normal),
        bodyEmphasis = sansStyle(fontSize = 16, lineHeight = 24, fontWeight = FontWeight.Medium),
        bodyEmphasisBold = sansStyle(fontSize = 16, lineHeight = 24, fontWeight = FontWeight.Bold),
        secondaryBody = sansStyle(fontSize = 14, lineHeight = 20, fontWeight = FontWeight.Normal),
        caption = sansStyle(fontSize = 12, lineHeight = 16, fontWeight = FontWeight.Normal),
        smallLabel = sansStyle(fontSize = 12, lineHeight = 16, fontWeight = FontWeight.Medium),
        button = sansStyle(fontSize = 16, lineHeight = 24, fontWeight = FontWeight.Medium),
        navLabel = sansStyle(fontSize = 12, lineHeight = 16, fontWeight = FontWeight.Medium),
        monoValue = monoStyle(fontSize = 14, lineHeight = 20),
        monoConfig = monoStyle(fontSize = 14, lineHeight = 20),
        monoInline = monoStyle(fontSize = 13, lineHeight = 20),
        monoLog = monoStyle(fontSize = 12, lineHeight = 20),
        monoSmall = monoStyle(fontSize = 12, lineHeight = 16),
        brandMark = pixelStyle(fontSize = 32, lineHeight = 48, letterSpacing = 0.8f),
        brandStatus = monoStyle(fontSize = 13, lineHeight = 18, fontWeight = FontWeight.Medium),
    )

private val DisplayLargeStyle = sansStyle(fontSize = 57, lineHeight = 64, fontWeight = FontWeight.Normal)
private val DisplayMediumStyle = sansStyle(fontSize = 45, lineHeight = 52, fontWeight = FontWeight.Normal)
private val DisplaySmallStyle = sansStyle(fontSize = 36, lineHeight = 44, fontWeight = FontWeight.Normal)
private val HeadlineLargeStyle = sansStyle(fontSize = 32, lineHeight = 40, fontWeight = FontWeight.Normal)
private val HeadlineMediumStyle = sansStyle(fontSize = 28, lineHeight = 36, fontWeight = FontWeight.Normal)

val RipDpiTypography = ripDpiTypography(RipDpiTypeScale)

private fun TextStyle.emphasized(): TextStyle =
    copy(fontWeight = if (fontWeight == FontWeight.Normal) FontWeight.Medium else FontWeight.Bold)

internal fun ripDpiTypography(type: RipDpiTextStyles): Typography {
    val locales = type.body.localeList

    val displayLarge = DisplayLargeStyle.copy(localeList = locales)
    val displayMedium = DisplayMediumStyle.copy(localeList = locales)
    val displaySmall = DisplaySmallStyle.copy(localeList = locales)
    val headlineLarge = HeadlineLargeStyle.copy(localeList = locales)
    val headlineMedium = HeadlineMediumStyle.copy(localeList = locales)
    return Typography(
        displayLarge = displayLarge,
        displayMedium = displayMedium,
        displaySmall = displaySmall,
        headlineLarge = headlineLarge,
        headlineMedium = headlineMedium,
        headlineSmall = type.screenTitle,
        titleLarge = type.appBarTitle,
        titleMedium = type.bodyEmphasis,
        titleSmall = type.sectionTitle,
        bodyLarge = type.body,
        bodyMedium = type.secondaryBody,
        bodySmall = type.caption,
        labelLarge = type.button,
        labelMedium = type.smallLabel,
        labelSmall = type.navLabel,
        displayLargeEmphasized = displayLarge.emphasized(),
        displayMediumEmphasized = displayMedium.emphasized(),
        displaySmallEmphasized = displaySmall.emphasized(),
        headlineLargeEmphasized = headlineLarge.emphasized(),
        headlineMediumEmphasized = headlineMedium.emphasized(),
        headlineSmallEmphasized = type.screenTitle.emphasized(),
        titleLargeEmphasized = type.appBarTitle.emphasized(),
        titleMediumEmphasized = type.bodyEmphasis.emphasized(),
        titleSmallEmphasized = type.sectionTitle.emphasized(),
        bodyLargeEmphasized = type.body.emphasized(),
        bodyMediumEmphasized = type.secondaryBody.emphasized(),
        bodySmallEmphasized = type.caption.emphasized(),
        labelLargeEmphasized = type.button.emphasized(),
        labelMediumEmphasized = type.smallLabel.emphasized(),
        labelSmallEmphasized = type.navLabel.emphasized(),
    )
}

internal val LocalRipDpiTextStyles = staticCompositionLocalOf { RipDpiTypeScale }
