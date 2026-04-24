package com.poyka.ripdpi.ui.theme

import androidx.compose.runtime.Immutable
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButtonStyle
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRowVariant
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogTone
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone

@Immutable
data class RipDpiCardSurfaceRoles(
    val outlined: RipDpiSurfaceRole = RipDpiSurfaceRole.Card,
    val tonal: RipDpiSurfaceRole = RipDpiSurfaceRole.TonalCard,
    val elevated: RipDpiSurfaceRole = RipDpiSurfaceRole.ElevatedCard,
    val status: RipDpiSurfaceRole = RipDpiSurfaceRole.StatusCard,
) {
    fun fromVariant(variant: RipDpiCardVariant): RipDpiSurfaceRole =
        when (variant) {
            RipDpiCardVariant.Outlined -> outlined
            RipDpiCardVariant.Tonal -> tonal
            RipDpiCardVariant.Elevated -> elevated
            RipDpiCardVariant.Status -> status
        }
}

@Immutable
data class RipDpiPresetCardSurfaceRoles(
    val defaultCard: RipDpiSurfaceRole = RipDpiSurfaceRole.Card,
    val selectedCard: RipDpiSurfaceRole = RipDpiSurfaceRole.SelectedCard,
    val defaultBadge: RipDpiSurfaceRole = RipDpiSurfaceRole.TonalCard,
    val selectedBadge: RipDpiSurfaceRole = RipDpiSurfaceRole.SelectedCard,
) {
    fun card(selected: Boolean): RipDpiSurfaceRole = if (selected) selectedCard else defaultCard

    fun badge(selected: Boolean): RipDpiSurfaceRole = if (selected) selectedBadge else defaultBadge
}

@Immutable
data class RipDpiFeedbackSurfaceRoles(
    val dialog: RipDpiSurfaceRole = RipDpiSurfaceRole.Dialog,
    val dialogIconBadge: RipDpiSurfaceRole = RipDpiSurfaceRole.DialogIconBadge,
    val dialogDestructiveIconBadge: RipDpiSurfaceRole = RipDpiSurfaceRole.DialogDestructiveIconBadge,
    val bottomSheet: RipDpiSurfaceRole = RipDpiSurfaceRole.BottomSheet,
    val bottomSheetIconBadge: RipDpiSurfaceRole = RipDpiSurfaceRole.BottomSheetIconBadge,
    val banner: RipDpiSurfaceRole = RipDpiSurfaceRole.Banner,
    val snackbar: RipDpiSurfaceRole = RipDpiSurfaceRole.Snackbar,
) {
    fun dialogIconBadge(tone: RipDpiDialogTone): RipDpiSurfaceRole =
        when (tone) {
            RipDpiDialogTone.Destructive -> dialogDestructiveIconBadge

            RipDpiDialogTone.Info,
            RipDpiDialogTone.Default,
            -> dialogIconBadge
        }
}

@Immutable
data class RipDpiInputSurfaceRoles(
    val dropdownMenu: RipDpiSurfaceRole = RipDpiSurfaceRole.DropdownMenu,
    val switchThumb: RipDpiSurfaceRole = RipDpiSurfaceRole.SwitchThumb,
)

@Immutable
data class RipDpiNavigationSurfaceRoles(
    val bottomBar: RipDpiSurfaceRole = RipDpiSurfaceRole.BottomBar,
    val bottomBarIndicator: RipDpiSurfaceRole = RipDpiSurfaceRole.BottomBarIndicator,
)

@Immutable
data class RipDpiActuatorSurfaceRoles(
    val rail: RipDpiSurfaceRole = RipDpiSurfaceRole.ActuatorRail,
    val carriage: RipDpiSurfaceRole = RipDpiSurfaceRole.ActuatorCarriage,
    val terminalSlot: RipDpiSurfaceRole = RipDpiSurfaceRole.ActuatorTerminalSlot,
    val pipelineSegment: RipDpiSurfaceRole = RipDpiSurfaceRole.ActuatorPipelineSegment,
)

@Immutable
data class RipDpiRouteSurfaceRoles(
    val profile: RipDpiSurfaceRole = RipDpiSurfaceRole.RouteProfile,
    val capability: RipDpiSurfaceRole = RipDpiSurfaceRole.RouteCapability,
    val stack: RipDpiSurfaceRole = RipDpiSurfaceRole.RouteStack,
    val provider: RipDpiSurfaceRole = RipDpiSurfaceRole.RouteProvider,
    val opportunity: RipDpiSurfaceRole = RipDpiSurfaceRole.RouteOpportunity,
)

@Immutable
data class RipDpiSurfaceRoleMappings(
    val cards: RipDpiCardSurfaceRoles = RipDpiCardSurfaceRoles(),
    val presetCard: RipDpiPresetCardSurfaceRoles = RipDpiPresetCardSurfaceRoles(),
    val feedback: RipDpiFeedbackSurfaceRoles = RipDpiFeedbackSurfaceRoles(),
    val inputs: RipDpiInputSurfaceRoles = RipDpiInputSurfaceRoles(),
    val navigation: RipDpiNavigationSurfaceRoles = RipDpiNavigationSurfaceRoles(),
    val actuator: RipDpiActuatorSurfaceRoles = RipDpiActuatorSurfaceRoles(),
    val routes: RipDpiRouteSurfaceRoles = RipDpiRouteSurfaceRoles(),
)

@Immutable
data class RipDpiButtonStateRoles(
    val primary: RipDpiButtonStateRole = RipDpiButtonStateRole.Primary,
    val secondary: RipDpiButtonStateRole = RipDpiButtonStateRole.Secondary,
    val outline: RipDpiButtonStateRole = RipDpiButtonStateRole.Outline,
    val ghost: RipDpiButtonStateRole = RipDpiButtonStateRole.Ghost,
    val destructive: RipDpiButtonStateRole = RipDpiButtonStateRole.Destructive,
) {
    fun fromVariant(variant: RipDpiButtonVariant): RipDpiButtonStateRole =
        when (variant) {
            RipDpiButtonVariant.Primary -> primary
            RipDpiButtonVariant.Secondary -> secondary
            RipDpiButtonVariant.Outline -> outline
            RipDpiButtonVariant.Ghost -> ghost
            RipDpiButtonVariant.Destructive -> destructive
        }
}

@Immutable
data class RipDpiIconButtonStateRoles(
    val ghost: RipDpiIconButtonStateRole = RipDpiIconButtonStateRole.Ghost,
    val tonal: RipDpiIconButtonStateRole = RipDpiIconButtonStateRole.Tonal,
    val filled: RipDpiIconButtonStateRole = RipDpiIconButtonStateRole.Filled,
    val outline: RipDpiIconButtonStateRole = RipDpiIconButtonStateRole.Outline,
) {
    fun fromStyle(style: RipDpiIconButtonStyle): RipDpiIconButtonStateRole =
        when (style) {
            RipDpiIconButtonStyle.Ghost -> ghost
            RipDpiIconButtonStyle.Tonal -> tonal
            RipDpiIconButtonStyle.Filled -> filled
            RipDpiIconButtonStyle.Outline -> outline
        }
}

@Immutable
data class RipDpiSettingsRowStateRoles(
    val default: RipDpiSettingsRowStateRole = RipDpiSettingsRowStateRole.Default,
    val tonal: RipDpiSettingsRowStateRole = RipDpiSettingsRowStateRole.Tonal,
    val selected: RipDpiSettingsRowStateRole = RipDpiSettingsRowStateRole.Selected,
) {
    fun fromVariant(variant: SettingsRowVariant): RipDpiSettingsRowStateRole =
        when (variant) {
            SettingsRowVariant.Default -> default
            SettingsRowVariant.Tonal -> tonal
            SettingsRowVariant.Selected -> selected
        }
}

@Immutable
data class RipDpiBannerStateRoles(
    val warning: RipDpiBannerStateRole = RipDpiBannerStateRole.Warning,
    val error: RipDpiBannerStateRole = RipDpiBannerStateRole.Error,
    val info: RipDpiBannerStateRole = RipDpiBannerStateRole.Info,
    val restricted: RipDpiBannerStateRole = RipDpiBannerStateRole.Restricted,
) {
    fun fromTone(tone: WarningBannerTone): RipDpiBannerStateRole =
        when (tone) {
            WarningBannerTone.Warning -> warning
            WarningBannerTone.Error -> error
            WarningBannerTone.Info -> info
            WarningBannerTone.Restricted -> restricted
        }
}

@Immutable
data class RipDpiActuatorStateRoles(
    val open: RipDpiActuatorStateRole = RipDpiActuatorStateRole.Open,
    val engaging: RipDpiActuatorStateRole = RipDpiActuatorStateRole.Engaging,
    val locked: RipDpiActuatorStateRole = RipDpiActuatorStateRole.Locked,
    val degraded: RipDpiActuatorStateRole = RipDpiActuatorStateRole.Degraded,
    val fault: RipDpiActuatorStateRole = RipDpiActuatorStateRole.Fault,
)

@Immutable
data class RipDpiActuatorStageRoles(
    val pending: RipDpiActuatorStageRole = RipDpiActuatorStageRole.Pending,
    val active: RipDpiActuatorStageRole = RipDpiActuatorStageRole.Active,
    val complete: RipDpiActuatorStageRole = RipDpiActuatorStageRole.Complete,
    val warning: RipDpiActuatorStageRole = RipDpiActuatorStageRole.Warning,
    val failed: RipDpiActuatorStageRole = RipDpiActuatorStageRole.Failed,
)

@Immutable
data class RipDpiRouteAvailabilityStateRoles(
    val available: RipDpiRouteAvailabilityStateRole = RipDpiRouteAvailabilityStateRole.Available,
    val selected: RipDpiRouteAvailabilityStateRole = RipDpiRouteAvailabilityStateRole.Selected,
    val configured: RipDpiRouteAvailabilityStateRole = RipDpiRouteAvailabilityStateRole.Configured,
    val needsSetup: RipDpiRouteAvailabilityStateRole = RipDpiRouteAvailabilityStateRole.NeedsSetup,
    val restricted: RipDpiRouteAvailabilityStateRole = RipDpiRouteAvailabilityStateRole.Restricted,
    val active: RipDpiRouteAvailabilityStateRole = RipDpiRouteAvailabilityStateRole.Active,
    val degraded: RipDpiRouteAvailabilityStateRole = RipDpiRouteAvailabilityStateRole.Degraded,
    val failed: RipDpiRouteAvailabilityStateRole = RipDpiRouteAvailabilityStateRole.Failed,
)

@Immutable
data class RipDpiStateRoleMappings(
    val button: RipDpiButtonStateRoles = RipDpiButtonStateRoles(),
    val iconButton: RipDpiIconButtonStateRoles = RipDpiIconButtonStateRoles(),
    val settingsRow: RipDpiSettingsRowStateRoles = RipDpiSettingsRowStateRoles(),
    val banner: RipDpiBannerStateRoles = RipDpiBannerStateRoles(),
    val actuator: RipDpiActuatorStateRoles = RipDpiActuatorStateRoles(),
    val actuatorStage: RipDpiActuatorStageRoles = RipDpiActuatorStageRoles(),
    val route: RipDpiRouteAvailabilityStateRoles = RipDpiRouteAvailabilityStateRoles(),
)

val DefaultRipDpiSurfaceRoleMappings = RipDpiSurfaceRoleMappings()
val DefaultRipDpiStateRoleMappings = RipDpiStateRoleMappings()
