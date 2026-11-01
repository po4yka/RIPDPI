@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.theme

import androidx.compose.foundation.style.CommonStyle
import androidx.compose.ui.graphics.Color

/** Surface recipes extend the same catalog as controls. All defaults use layer zero. */
internal fun RipDpiComponentStyles.presetBadge(role: RipDpiSurfaceRole): CommonStyle = PresetBadgeStyles.getValue(role)

internal val RipDpiComponentStyles.accordion: CommonStyle
    get() = AccordionStyle

internal val RipDpiComponentStyles.presetRadio: CommonStyle
    get() = PresetRadioStyle

internal val RipDpiComponentStyles.presetRadioDot: CommonStyle
    get() = PresetRadioDotStyle

internal fun RipDpiComponentStyles.routeProfile(role: RipDpiRouteAvailabilityStateRole): CommonStyle =
    RouteProfileStyles.getValue(role)

internal fun RipDpiComponentStyles.routeBadge(role: RipDpiRouteAvailabilityStateRole): CommonStyle =
    RouteBadgeStyles.getValue(role)

internal fun RipDpiComponentStyles.routeOpportunity(role: RipDpiRouteAvailabilityStateRole): CommonStyle =
    RouteOpportunityStyles.getValue(role)

internal fun RipDpiComponentStyles.routeConnector(role: RipDpiRouteAvailabilityStateRole): CommonStyle =
    RouteConnectorStyles.getValue(role)

internal fun RipDpiComponentStyles.actuatorFault(role: RipDpiActuatorStateRole): CommonStyle =
    ActuatorFaultStyles.getValue(role)

/** A preset card's badge: [container] is its fill and [content] the label drawn on it. */
internal data class PresetBadgeColors(
    val container: Color,
    val content: Color,
)

/**
 * A selected badge is inverted against its card, so its label takes the card's container color, the one that
 * [RipDpiSurfaceStyle.content] is guaranteed to contrast with. An unselected badge keeps the muted label.
 */
internal fun presetBadgeColors(
    surface: RipDpiSurfaceStyle,
    selected: Boolean,
    mutedForeground: Color,
): PresetBadgeColors =
    if (selected) {
        PresetBadgeColors(container = surface.content, content = surface.container)
    } else {
        PresetBadgeColors(container = surface.container, content = mutedForeground)
    }

private val PresetBadgeStyles =
    RipDpiSurfaceRole.entries.associateWith { role ->
        CommonStyle {
            val surface = LocalRipDpiSurfaceTokens.currentValue.resolve(role)
            val colors = LocalRipDpiExtendedColors.currentValue
            rdsBackground(presetBadgeColors(surface, state.isSelected, colors.mutedForeground).container)
        }
    }

private val AccordionStyle =
    CommonStyle {
        val colors = LocalRipDpiExtendedColors.currentValue
        rdsBackground(colors.card)
        rdsBorder(colors.cardBorder, RipDpiStroke.Thin)
    }

private val PresetRadioStyle =
    CommonStyle {
        val colors = LocalRipDpiExtendedColors.currentValue
        rdsBorder(if (state.isSelected) colors.foreground else colors.mutedForeground, RipDpiStroke.Thick)
    }

private val PresetRadioDotStyle =
    CommonStyle {
        val colors = LocalRipDpiExtendedColors.currentValue
        rdsBackground(if (state.isSelected) colors.foreground else colors.mutedForeground)
    }

private val RouteProfileStyles =
    RipDpiRouteAvailabilityStateRole.entries.associateWith { role ->
        CommonStyle {
            val surface = LocalRipDpiSurfaceTokens.currentValue.resolve(DefaultRipDpiSurfaceRoleMappings.routes.profile)
            val route = LocalRipDpiStateTokens.currentValue.route.resolve(role)
            rdsBackground(if (state.isSelected) route.container else surface.container)
            rdsBorder(
                if (state.isSelected ||
                    state[RipDpiStyleStateKeys.Active]
                ) {
                    route.border
                } else {
                    surface.border
                },
                route.borderWidth,
            )
        }
    }

private val RouteBadgeStyles =
    RipDpiRouteAvailabilityStateRole.entries.associateWith { role ->
        CommonStyle {
            val route = LocalRipDpiStateTokens.currentValue.route.resolve(role)
            rdsBackground(route.badgeContainer)
            rdsBorder(route.badgeBorder, RipDpiStroke.Thin)
        }
    }

private val RouteOpportunityStyles =
    RipDpiRouteAvailabilityStateRole.entries.associateWith { role ->
        CommonStyle {
            val surface =
                LocalRipDpiSurfaceTokens.currentValue.resolve(
                    DefaultRipDpiSurfaceRoleMappings.routes.opportunity,
                )
            val route = LocalRipDpiStateTokens.currentValue.route.resolve(role)
            rdsBackground(surface.container)
            rdsBorder(route.border, RipDpiStroke.Thin)
        }
    }

private val RouteConnectorStyles =
    RipDpiRouteAvailabilityStateRole.entries.associateWith { role ->
        CommonStyle {
            rdsBackground(
                LocalRipDpiStateTokens.currentValue.route
                    .resolve(role)
                    .border,
            )
        }
    }

private val ActuatorFaultStyles =
    RipDpiActuatorStateRole.entries.associateWith { role ->
        CommonStyle {
            val resolved = LocalRipDpiStateTokens.currentValue.actuator.resolve(role)
            rdsBackground(resolved.rail)
            rdsBorder(resolved.railBorder, RipDpiStroke.Thin)
        }
    }
