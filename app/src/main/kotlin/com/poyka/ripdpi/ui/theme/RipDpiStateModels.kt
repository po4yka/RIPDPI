package com.poyka.ripdpi.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

enum class RipDpiButtonStateRole {
    Primary,
    Secondary,
    Outline,
    Ghost,
    Destructive,
}

enum class RipDpiIconButtonStateRole {
    Ghost,
    Tonal,
    Filled,
    Outline,
    Destructive,
    Warning,
}

enum class RipDpiSettingsRowStateRole {
    Default,
    Tonal,
    Selected,
}

enum class RipDpiBannerStateRole {
    Warning,
    Error,
    Info,
    Restricted,
}

enum class RipDpiActuatorStateRole {
    Open,
    Engaging,
    Locked,
    Degraded,
    Fault,
}

enum class RipDpiActuatorStageRole {
    Pending,
    Active,
    Complete,
    Warning,
    Failed,
}

enum class RipDpiRouteAvailabilityStateRole {
    Available,
    Selected,
    Configured,
    NeedsSetup,
    Restricted,
    Active,
    Degraded,
    Failed,
}

@Immutable
data class RipDpiButtonStateStyle(
    val container: Color,
    val content: Color,
    val border: Color,
    val borderWidth: Dp,
    val cornerRadius: Dp,
    val scale: Float,
    val contentAlpha: Float,
)

@Immutable
data class RipDpiIconButtonStateStyle(
    val container: Color,
    val content: Color,
    val border: Color,
    val borderWidth: Dp,
    val scale: Float,
)

@Immutable
data class RipDpiTextFieldStateStyle(
    val container: Color,
    val border: Color,
    val borderWidth: Dp,
    val content: Color,
    val label: Color,
    val helper: Color,
    val placeholder: Color,
    val alpha: Float,
)

@Immutable
data class RipDpiChipStateStyle(
    val container: Color,
    val border: Color,
    val content: Color,
    val cornerRadius: Dp,
    val scale: Float,
    val alpha: Float,
)

@Immutable
data class RipDpiSwitchStateStyle(
    val track: Color,
    val thumb: Color,
    val alpha: Float,
)

@Immutable
data class RipDpiSettingsRowStateStyle(
    val container: Color,
    val border: Color,
    val title: Color,
    val subtitle: Color,
    val value: Color,
    val leadingBadgeContainer: Color,
    val leadingBadgeIcon: Color,
)

@Immutable
data class RipDpiBannerStateStyle(
    val container: Color,
    val border: Color,
    val iconContainer: Color,
    val icon: Color,
    val title: Color,
    val message: Color,
)

@Immutable
data class RipDpiActuatorStateStyle(
    val rail: Color,
    val railBorder: Color,
    val carriage: Color,
    val carriageContent: Color,
    val terminal: Color,
    val terminalBorder: Color,
    val label: Color,
    val routeLabel: Color,
    val slotContent: Color,
)

@Immutable
data class RipDpiActuatorStageStyle(
    val container: Color,
    val border: Color,
    val content: Color,
    val striped: Boolean,
    val pulsing: Boolean,
)

@Immutable
data class RipDpiRouteAvailabilityStateStyle(
    val container: Color,
    val border: Color,
    val content: Color,
    val mutedContent: Color,
    val marker: Color,
    val badgeContainer: Color,
    val badgeBorder: Color,
    val badgeContent: Color,
    val borderWidth: Dp,
    val alpha: Float,
)
