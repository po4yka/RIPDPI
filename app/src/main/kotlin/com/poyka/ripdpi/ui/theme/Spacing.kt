package com.poyka.ripdpi.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val expandedWidthBreakpointDp = 840
private const val mediumWidthBreakpointDp = 600

enum class RipDpiWidthClass {
    Compact,
    Medium,
    Expanded,
}

enum class RipDpiContentGrouping {
    SingleColumn,
    CenteredColumn,
    SplitColumns,
}

@Immutable
data class RipDpiSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val xxxl: Dp = 32.dp,
    val section: Dp = 40.dp,
    val screen: Dp = 48.dp,
)

@Immutable
data class RipDpiLayout(
    val widthClass: RipDpiWidthClass = RipDpiWidthClass.Compact,
    val contentGrouping: RipDpiContentGrouping = RipDpiContentGrouping.SingleColumn,
    val horizontalPadding: Dp = 20.dp,
    val contentMaxWidth: Dp = 560.dp,
    val formMaxWidth: Dp = 520.dp,
    val dialogMaxWidth: Dp = 560.dp,
    val snackbarMaxWidth: Dp = 560.dp,
    val cardPadding: Dp = 16.dp,
    val sectionGap: Dp = 20.dp,
    val groupGap: Dp = 16.dp,
    val appBarMinHeight: Dp = 56.dp,
    val bottomBarHeight: Dp = 64.dp,
)

@Immutable
data class RipDpiShapeMetrics(
    val extraSmallCornerRadius: Dp = 4.dp,
    val compactCornerRadius: Dp = 8.dp,
    val mediumCornerRadius: Dp = 10.dp,
    val largeCornerRadius: Dp = 12.dp,
    val controlCornerRadius: Dp = 16.dp,
    val controlIncreasedCornerRadius: Dp = 20.dp,
    val cardCornerRadius: Dp = 16.dp,
    val chipCornerRadius: Dp = 12.dp,
    val pillCornerRadius: Dp = 28.dp,
    val pillIncreasedCornerRadius: Dp = 32.dp,
    val heroCornerRadius: Dp = 48.dp,
)

@Immutable
data class RipDpiButtonMetrics(
    val minHeight: Dp = 48.dp,
    val horizontalPadding: Dp = 20.dp,
    val focusedHorizontalPaddingOffset: Dp = 4.dp,
    val iconGap: Dp = 8.dp,
    val verticalPadding: Dp = 10.dp,
    val iconButtonSize: Dp = 48.dp,
)

@Immutable
data class RipDpiInputMetrics(
    val controlHeight: Dp = 48.dp,
    val fieldHorizontalPadding: Dp = 17.dp,
    val fieldFocusedHorizontalPadding: Dp = 18.dp,
    val textFieldLabelGap: Dp = 6.dp,
    val multilineFieldMinHeight: Dp = 96.dp,
    val chipHorizontalPadding: Dp = 17.dp,
    val chipFocusedHorizontalPaddingOffset: Dp = 4.dp,
    val chipVerticalPadding: Dp = 6.dp,
    val chipFocusedVerticalPaddingOffset: Dp = 2.dp,
    val chipIconSize: Dp = 14.dp,
    val chipHeight: Dp = 64.dp,
    val switchWidth: Dp = 52.dp,
    val switchHeight: Dp = 48.dp,
    val switchTrackHeight: Dp = 32.dp,
    val switchThumbSize: Dp = 24.dp,
    val switchThumbPadding: Dp = 4.dp,
    val stepperOuterPadding: Dp = 2.dp,
    val stepperButtonSize: Dp = 32.dp,
    val stepperButtonCornerRadius: Dp = 6.dp,
    val stepperValueMinWidth: Dp = 48.dp,
    val stepperIconSize: Dp = 16.dp,
)

@Immutable
data class RipDpiActuatorMetrics(
    val railHeight: Dp = 56.dp,
    val trackInset: Dp = 6.dp,
    val carriageWidth: Dp = 76.dp,
    val carriageHeight: Dp = 44.dp,
    val terminalSlotWidth: Dp = 56.dp,
    val terminalSlotHeight: Dp = 44.dp,
    val pipelineHeight: Dp = 28.dp,
    val gripWidth: Dp = 2.dp,
    val gripHeight: Dp = 16.dp,
    val stageIconSize: Dp = 12.dp,
    val stageHorizontalPadding: Dp = 5.dp,
    val stageIconGap: Dp = 3.dp,
)

@Immutable
data class RipDpiRowMetrics(
    val compactPillHorizontalPadding: Dp = 8.dp,
    val compactPillVerticalPadding: Dp = 2.dp,
    val settingsRowVerticalPadding: Dp = 14.dp,
    val settingsRowMinHeight: Dp = 52.dp,
    val settingsRowMinHeightWithSubtitle: Dp = 68.dp,
)

@Immutable
data class RipDpiSheetMetrics(
    val handleTopPadding: Dp = 12.dp,
    val handleWidth: Dp = 36.dp,
    val handleHeight: Dp = 4.dp,
)

@Immutable
data class RipDpiNavigationMetrics(
    val bottomNavIndicatorWidth: Dp = 64.dp,
    val bottomNavIndicatorHeight: Dp = 28.dp,
    val bottomNavHorizontalPadding: Dp = 0.dp,
    val bottomNavIndicatorTopOffset: Dp = 10.dp,
    val railItemLabelGap: Dp = 2.dp,
)

@Immutable
data class RipDpiIndicatorMetrics(
    val statusMarkerSmall: Dp = 8.dp,
    val statusMarkerMedium: Dp = 9.dp,
    val statusMarkerLarge: Dp = 10.dp,
    val statusMarkerSpacing: Dp = 8.dp,
    val statusMarkerCompact: Dp = 14.dp,
    val statusMarkerHero: Dp = 20.dp,
    val spinnerSmallSize: Dp = 16.dp,
    val spinnerSmallStroke: Dp = 1.5.dp,
    val spinnerStandardSize: Dp = 24.dp,
    val spinnerStandardStroke: Dp = 2.dp,
    val spinnerLargeSize: Dp = 40.dp,
    val spinnerLargeStroke: Dp = 3.dp,
    val staleBadgeVerticalPadding: Dp = 3.dp,
    val staleBadgeDotSize: Dp = 6.dp,
    val progressSegmentHeight: Dp = 6.dp,
)

@Immutable
data class RipDpiFeedbackMetrics(
    val dialogIconSize: Dp = 40.dp,
    val decorativeBadgeSize: Dp = 28.dp,
)

@Immutable
data class RipDpiDiffMetrics(
    val rowGap: Dp = 1.dp,
    val rowVerticalPadding: Dp = 2.dp,
    val unifiedHorizontalPadding: Dp = 6.dp,
    val unifiedColumnGap: Dp = 8.dp,
    val sideBySideColumnGap: Dp = 4.dp,
    val sideBySideHorizontalPadding: Dp = 4.dp,
)

@Immutable
data class RipDpiJsonTreeMetrics(
    val rowVerticalPadding: Dp = 2.dp,
    val chevronSize: Dp = 14.dp,
)

@Immutable
data class RipDpiTooltipMetrics(
    val richMaxWidth: Dp = 320.dp,
)

@Immutable
data class RipDpiContentMetrics(
    val scrollableNarrativeMaxHeight: Dp = 220.dp,
)

@Immutable
data class RipDpiComponents(
    val shapes: RipDpiShapeMetrics = RipDpiShapeMetrics(),
    val buttons: RipDpiButtonMetrics = RipDpiButtonMetrics(),
    val inputs: RipDpiInputMetrics = RipDpiInputMetrics(),
    val actuator: RipDpiActuatorMetrics = RipDpiActuatorMetrics(),
    val rows: RipDpiRowMetrics = RipDpiRowMetrics(),
    val sheets: RipDpiSheetMetrics = RipDpiSheetMetrics(),
    val navigation: RipDpiNavigationMetrics = RipDpiNavigationMetrics(),
    val indicators: RipDpiIndicatorMetrics = RipDpiIndicatorMetrics(),
    val feedback: RipDpiFeedbackMetrics = RipDpiFeedbackMetrics(),
    val diff: RipDpiDiffMetrics = RipDpiDiffMetrics(),
    val jsonTree: RipDpiJsonTreeMetrics = RipDpiJsonTreeMetrics(),
    val tooltip: RipDpiTooltipMetrics = RipDpiTooltipMetrics(),
    val content: RipDpiContentMetrics = RipDpiContentMetrics(),
)

object RipDpiStroke {
    val Hairline = 0.5.dp
    val Thin = 1.dp
    val Thick = 2.dp
}

val DefaultRipDpiSpacing = RipDpiSpacing()
val DefaultRipDpiLayout = ripDpiLayoutForWidth(screenWidthDp = 360)
val DefaultRipDpiComponents = RipDpiComponents()

fun ripDpiWidthClassForWidth(screenWidthDp: Int): RipDpiWidthClass =
    when {
        screenWidthDp >= expandedWidthBreakpointDp -> RipDpiWidthClass.Expanded
        screenWidthDp >= mediumWidthBreakpointDp -> RipDpiWidthClass.Medium
        else -> RipDpiWidthClass.Compact
    }

fun ripDpiLayoutForWidth(screenWidthDp: Int): RipDpiLayout =
    when (ripDpiWidthClassForWidth(screenWidthDp = screenWidthDp)) {
        RipDpiWidthClass.Compact -> {
            RipDpiLayout(
                widthClass = RipDpiWidthClass.Compact,
                contentGrouping = RipDpiContentGrouping.SingleColumn,
                horizontalPadding = 20.dp,
                contentMaxWidth = 560.dp,
                formMaxWidth = 520.dp,
                dialogMaxWidth = 560.dp,
                snackbarMaxWidth = 560.dp,
                cardPadding = 16.dp,
                sectionGap = 20.dp,
                groupGap = 16.dp,
                appBarMinHeight = 56.dp,
                bottomBarHeight = 64.dp,
            )
        }

        RipDpiWidthClass.Medium -> {
            RipDpiLayout(
                widthClass = RipDpiWidthClass.Medium,
                contentGrouping = RipDpiContentGrouping.CenteredColumn,
                horizontalPadding = 28.dp,
                contentMaxWidth = 720.dp,
                formMaxWidth = 600.dp,
                dialogMaxWidth = 640.dp,
                snackbarMaxWidth = 640.dp,
                cardPadding = 18.dp,
                sectionGap = 24.dp,
                groupGap = 20.dp,
                appBarMinHeight = 60.dp,
                bottomBarHeight = 72.dp,
            )
        }

        RipDpiWidthClass.Expanded -> {
            RipDpiLayout(
                widthClass = RipDpiWidthClass.Expanded,
                contentGrouping = RipDpiContentGrouping.SplitColumns,
                horizontalPadding = 32.dp,
                contentMaxWidth = 960.dp,
                formMaxWidth = 680.dp,
                dialogMaxWidth = 720.dp,
                snackbarMaxWidth = 720.dp,
                cardPadding = 20.dp,
                sectionGap = 28.dp,
                groupGap = 24.dp,
                appBarMinHeight = 64.dp,
                bottomBarHeight = 72.dp,
            )
        }
    }

internal val LocalRipDpiSpacing = staticCompositionLocalOf { DefaultRipDpiSpacing }
internal val LocalRipDpiLayout = staticCompositionLocalOf { DefaultRipDpiLayout }
internal val LocalRipDpiComponents = staticCompositionLocalOf { DefaultRipDpiComponents }
