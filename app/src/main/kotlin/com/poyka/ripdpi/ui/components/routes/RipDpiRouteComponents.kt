@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.components.routes

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.components.ripDpiSelectable
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiRouteAvailabilityStateRole
import com.poyka.ripdpi.ui.theme.RipDpiStyleStateKeys
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.rememberRipDpiStyleResolver
import com.poyka.ripdpi.ui.theme.ripDpiStyleSurface
import com.poyka.ripdpi.ui.theme.routeBadge
import com.poyka.ripdpi.ui.theme.routeConnector
import com.poyka.ripdpi.ui.theme.routeOpportunity
import com.poyka.ripdpi.ui.theme.routeProfile
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

enum class RipDpiRouteTransportKind(
    val stableKey: String,
) {
    LocalVpn("local-vpn"),
    LocalProxy("local-proxy"),
    Warp("warp"),
    Relay("relay"),
    ExternalVpn("external-vpn"),
    AdvancedTechnique("advanced-technique"),
}

enum class RipDpiRouteCapabilityKind(
    val stableKey: String,
) {
    VpnPrivacy("vpn-privacy"),
    DnsProtection("dns-protection"),
    AntiDpi("anti-dpi"),
    Relay("relay"),
    FullTunnel("full-tunnel"),
    SplitTunnel("split-tunnel"),
    TrafficModification("traffic-modification"),
}

enum class RipDpiRouteAvailabilityState {
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
data class RipDpiRouteCapabilityUiState(
    val kind: RipDpiRouteCapabilityKind,
    val label: String,
    val state: RipDpiRouteAvailabilityState = RipDpiRouteAvailabilityState.Available,
)

@Immutable
data class RipDpiRouteProfileUiState(
    val id: String,
    val title: String,
    val subtitle: String,
    val transportLabel: String,
    val providerLabel: String,
    val capabilities: ImmutableList<RipDpiRouteCapabilityUiState>,
    val state: RipDpiRouteAvailabilityState = RipDpiRouteAvailabilityState.Available,
    val isSelected: Boolean = false,
    val isActive: Boolean = false,
)

@Immutable
data class RipDpiRouteStackNodeUiState(
    val id: String,
    val label: String,
    val transportKind: RipDpiRouteTransportKind,
    val state: RipDpiRouteAvailabilityState = RipDpiRouteAvailabilityState.Available,
)

@Immutable
data class RipDpiRouteStackUiState(
    val nodes: ImmutableList<RipDpiRouteStackNodeUiState>,
    val activeNodeId: String? = null,
    val warningNodeId: String? = null,
    val failedNodeId: String? = null,
)

@Composable
@NonRestartableComposable
fun RipDpiRouteProfileCard(
    state: RipDpiRouteProfileUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = state.state != RipDpiRouteAvailabilityState.Restricted,
) {
    RipDpiRouteProfileCard(
        state = state,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        visualStyle = CommonStyle,
    )
}

@Composable
fun RipDpiRouteProfileCard(
    state: RipDpiRouteProfileUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = state.state != RipDpiRouteAvailabilityState.Restricted,
    visualStyle: CommonStyle = CommonStyle,
) {
    val components = RipDpiThemeTokens.components
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val shape = RipDpiThemeTokens.shapes.xl
    val surface =
        RipDpiThemeTokens.surfaces.resolve(
            RipDpiThemeTokens.surfaceRoles.routes.profile,
        )
    val routeState = RipDpiThemeTokens.state.route.resolve(state.state.toThemeRole())
    val effectiveContent = if (state.isSelected) routeState.content else surface.content
    val interactionSource = remember { MutableInteractionSource() }
    val styleState =
        rememberUpdatedStyleState(interactionSource) {
            it.isEnabled = enabled
            it.isSelected = state.isSelected
            it[RipDpiStyleStateKeys.Active] = state.isActive
        }
    val styleResolver =
        rememberRipDpiStyleResolver(
            RipDpiThemeTokens.styles.routeProfile(state.state.toThemeRole()),
            visualStyle,
            styleState,
        )

    Column(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.routeProfile(state.id))
                .fillMaxWidth()
                .heightIn(min = components.rows.settingsRowMinHeightWithSubtitle)
                .shadow(surface.shadowElevation, shape, clip = false)
                .ripDpiStyleSurface(styleResolver, shape, clip = true)
                .alpha(if (enabled) routeState.alpha else routeState.alpha * RouteDisabledAlpha)
                .ripDpiSelectable(
                    selected = state.isSelected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    hapticFeedback = RipDpiHapticFeedback.Selection,
                    interactionSource = interactionSource,
                    onClick = onClick,
                ).padding(components.inputs.fieldHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        RouteProfileHeader(
            state = state,
            effectiveContent = effectiveContent,
            routeState = routeState,
        )
        Text(
            text = state.subtitle,
            style = type.secondaryBody,
            color = routeState.mutedContent,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            state.capabilities.forEach { capability ->
                RipDpiRouteCapabilityPill(capability = capability)
            }
        }
    }
}

@Composable
private fun RouteProfileHeader(
    state: RipDpiRouteProfileUiState,
    effectiveContent: androidx.compose.ui.graphics.Color,
    routeState: com.poyka.ripdpi.ui.theme.RipDpiRouteAvailabilityStateStyle,
) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RouteTransportGlyph(
            icon = state.transportIcon(),
            state = state.state,
            active = state.isActive,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.title,
                style = type.bodyEmphasis,
                color = effectiveContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${state.providerLabel} · ${state.transportLabel}",
                style = type.monoSmall,
                color = routeState.mutedContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RouteStateBadge(state = state.state, active = state.isActive)
    }
}

@Composable
@NonRestartableComposable
fun RipDpiRouteCapabilityPill(
    capability: RipDpiRouteCapabilityUiState,
    modifier: Modifier = Modifier,
) {
    RipDpiRouteCapabilityPill(
        capability = capability,
        modifier = modifier,
        visualStyle = CommonStyle,
    )
}

@Composable
fun RipDpiRouteCapabilityPill(
    capability: RipDpiRouteCapabilityUiState,
    modifier: Modifier = Modifier,
    visualStyle: CommonStyle = CommonStyle,
) {
    val components = RipDpiThemeTokens.components
    val shape = RipDpiThemeTokens.shapes.xxl
    val state = RipDpiThemeTokens.state.route.resolve(capability.state.toThemeRole())
    val styleResolver =
        rememberRipDpiStyleResolver(
            RipDpiThemeTokens.styles.routeBadge(capability.state.toThemeRole()),
            visualStyle,
            rememberUpdatedStyleState(null),
        )

    Row(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.routeCapability(capability.kind.stableKey))
                .ripDpiStyleSurface(styleResolver, shape, clip = true)
                .padding(
                    horizontal = components.rows.compactPillHorizontalPadding,
                    vertical = components.rows.compactPillVerticalPadding,
                ),
        horizontalArrangement = Arrangement.spacedBy(RoutePillIconGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = capability.kind.icon(),
            contentDescription = null,
            tint = state.badgeContent,
            modifier = Modifier.size(RipDpiIconSizes.Small),
        )
        Text(
            text = capability.label,
            style = RipDpiThemeTokens.type.smallLabel,
            color = state.badgeContent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
@NonRestartableComposable
fun RipDpiRouteStackDiagram(
    state: RipDpiRouteStackUiState,
    modifier: Modifier = Modifier,
) {
    RipDpiRouteStackDiagram(
        state = state,
        modifier = modifier,
        visualStyle = CommonStyle,
    )
}

@Composable
fun RipDpiRouteStackDiagram(
    state: RipDpiRouteStackUiState,
    modifier: Modifier = Modifier,
    visualStyle: CommonStyle = CommonStyle,
) {
    val spacing = RipDpiThemeTokens.spacing
    val shape = RipDpiThemeTokens.shapes.lg
    val styleResolver =
        rememberRipDpiStyleResolver(
            RipDpiThemeTokens.styles.outlinedSurface(RipDpiThemeTokens.surfaceRoles.routes.stack),
            visualStyle,
            rememberUpdatedStyleState(null),
        )
    val description = state.accessibilityDescription()

    Row(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.RouteStack)
                .fillMaxWidth()
                .ripDpiStyleSurface(styleResolver, shape, clip = true)
                .semantics { contentDescription = description }
                .padding(spacing.md),
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.nodes.forEachIndexed { index, node ->
            val failedIndex = state.nodes.indexOfFirst { candidate -> candidate.id == state.failedNodeId }
            RouteStackNode(
                node = node,
                active = node.id == state.activeNodeId,
                warning = node.id == state.warningNodeId,
                failed = node.id == state.failedNodeId,
            )
            if (index < state.nodes.lastIndex) {
                RouteStackConnector(
                    state =
                        if (failedIndex >= 0 && failedIndex <= index) {
                            RipDpiRouteAvailabilityState.Failed
                        } else {
                            RipDpiRouteAvailabilityState.Configured
                        },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
@NonRestartableComposable
fun RipDpiRouteOpportunityPanel(
    title: String,
    message: String,
    state: RipDpiRouteAvailabilityState,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    RipDpiRouteOpportunityPanel(
        title = title,
        message = message,
        state = state,
        modifier = modifier,
        actionLabel = actionLabel,
        onAction = onAction,
        visualStyle = CommonStyle,
    )
}

@Composable
fun RipDpiRouteOpportunityPanel(
    title: String,
    message: String,
    state: RipDpiRouteAvailabilityState,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    visualStyle: CommonStyle = CommonStyle,
    onAction: (() -> Unit)? = null,
) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val shape = RipDpiThemeTokens.shapes.xl
    val routeState = RipDpiThemeTokens.state.route.resolve(state.toThemeRole())
    val interactionSource = remember { MutableInteractionSource() }
    val styleResolver =
        rememberRipDpiStyleResolver(
            RipDpiThemeTokens.styles.routeOpportunity(state.toThemeRole()),
            visualStyle,
            rememberUpdatedStyleState(interactionSource),
        )
    val actionModifier =
        if (onAction != null) {
            Modifier.ripDpiClickable(
                onClickLabel = actionLabel,
                interactionSource = interactionSource,
                onClick = onAction,
            )
        } else {
            Modifier
        }

    Column(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.RouteOpportunityPanel)
                .fillMaxWidth()
                .ripDpiStyleSurface(styleResolver, shape, clip = true)
                .then(actionModifier)
                .padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RouteTransportGlyph(
                icon = RipDpiIcons.Shield,
                state = state,
                active = state == RipDpiRouteAvailabilityState.Active,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = type.bodyEmphasis, color = routeState.content)
                Text(text = message, style = type.secondaryBody, color = routeState.mutedContent)
            }
            actionLabel?.let {
                Text(text = it, style = type.smallLabel, color = routeState.marker)
            }
        }
    }
}

@Composable
private fun RouteTransportGlyph(
    icon: ImageVector,
    state: RipDpiRouteAvailabilityState,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val role =
        if (active) {
            RipDpiRouteAvailabilityStateRole.Active
        } else {
            state.toThemeRole()
        }
    val style = RipDpiThemeTokens.state.route.resolve(role)
    val styleResolver =
        rememberRipDpiStyleResolver(
            RipDpiThemeTokens.styles.routeBadge(role),
            CommonStyle,
            rememberUpdatedStyleState(null),
        )

    Box(
        modifier =
            modifier
                .size(RouteGlyphSize)
                .ripDpiStyleSurface(styleResolver, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = style.badgeContent,
            modifier = Modifier.size(RipDpiIconSizes.Default),
        )
    }
}

@Composable
private fun RouteStateBadge(
    state: RipDpiRouteAvailabilityState,
    active: Boolean,
) {
    val label = if (active) stringResource(R.string.route_state_active) else state.label()
    val role = if (active) RipDpiRouteAvailabilityStateRole.Active else state.toThemeRole()
    val style = RipDpiThemeTokens.state.route.resolve(role)
    val styleResolver =
        rememberRipDpiStyleResolver(
            RipDpiThemeTokens.styles.routeBadge(role),
            CommonStyle,
            rememberUpdatedStyleState(null),
        )

    Text(
        text = label,
        style = RipDpiThemeTokens.type.smallLabel,
        color = style.badgeContent,
        modifier =
            Modifier
                .ripDpiStyleSurface(styleResolver, RipDpiThemeTokens.shapes.xxl, clip = true)
                .padding(
                    horizontal = RipDpiThemeTokens.components.rows.compactPillHorizontalPadding,
                    vertical = RipDpiThemeTokens.components.rows.compactPillVerticalPadding,
                ),
    )
}

@Composable
private fun RouteStackNode(
    node: RipDpiRouteStackNodeUiState,
    active: Boolean,
    warning: Boolean,
    failed: Boolean,
) {
    val nodeState =
        when {
            failed -> RipDpiRouteAvailabilityState.Failed
            warning -> RipDpiRouteAvailabilityState.Degraded
            active -> RipDpiRouteAvailabilityState.Active
            else -> node.state
        }
    val style = RipDpiThemeTokens.state.route.resolve(nodeState.toThemeRole())

    Column(
        modifier = Modifier.widthIn(min = RouteStackNodeMinWidth, max = RouteStackNodeMaxWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
    ) {
        RouteTransportGlyph(icon = node.transportKind.icon(), state = nodeState, active = active)
        Text(
            text = node.label,
            style = RipDpiThemeTokens.type.monoSmall,
            color = style.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RouteStackConnector(
    state: RipDpiRouteAvailabilityState,
    modifier: Modifier = Modifier,
) {
    val styleResolver =
        rememberRipDpiStyleResolver(
            RipDpiThemeTokens.styles.routeConnector(state.toThemeRole()),
            CommonStyle,
            rememberUpdatedStyleState(null),
        )

    Box(
        modifier =
            modifier
                .height(RouteConnectorHeight)
                .ripDpiStyleSurface(styleResolver, RipDpiThemeTokens.shapes.full),
    )
}

private fun RipDpiRouteProfileUiState.transportIcon(): ImageVector =
    when {
        isActive -> RipDpiIcons.Lock
        else -> transportKindFromCapability().icon()
    }

private fun RipDpiRouteProfileUiState.transportKindFromCapability(): RipDpiRouteTransportKind =
    when {
        transportLabel.contains("proxy", ignoreCase = true) -> RipDpiRouteTransportKind.LocalProxy
        transportLabel.contains("warp", ignoreCase = true) -> RipDpiRouteTransportKind.Warp
        transportLabel.contains("relay", ignoreCase = true) -> RipDpiRouteTransportKind.Relay
        transportLabel.contains("provider", ignoreCase = true) -> RipDpiRouteTransportKind.ExternalVpn
        transportLabel.contains("technique", ignoreCase = true) -> RipDpiRouteTransportKind.AdvancedTechnique
        else -> RipDpiRouteTransportKind.LocalVpn
    }

private fun RipDpiRouteCapabilityKind.icon(): ImageVector =
    when (this) {
        RipDpiRouteCapabilityKind.VpnPrivacy -> RipDpiIcons.Vpn
        RipDpiRouteCapabilityKind.DnsProtection -> RipDpiIcons.Dns
        RipDpiRouteCapabilityKind.AntiDpi -> RipDpiIcons.Shield
        RipDpiRouteCapabilityKind.Relay -> RipDpiIcons.NetworkCheck
        RipDpiRouteCapabilityKind.FullTunnel -> RipDpiIcons.Lock
        RipDpiRouteCapabilityKind.SplitTunnel -> RipDpiIcons.Config
        RipDpiRouteCapabilityKind.TrafficModification -> RipDpiIcons.Advanced
    }

private fun RipDpiRouteTransportKind.icon(): ImageVector =
    when (this) {
        RipDpiRouteTransportKind.LocalVpn -> RipDpiIcons.Vpn
        RipDpiRouteTransportKind.LocalProxy -> RipDpiIcons.NetworkCheck
        RipDpiRouteTransportKind.Warp -> RipDpiIcons.Public
        RipDpiRouteTransportKind.Relay -> RipDpiIcons.Share
        RipDpiRouteTransportKind.ExternalVpn -> RipDpiIcons.Shield
        RipDpiRouteTransportKind.AdvancedTechnique -> RipDpiIcons.Advanced
    }

private fun RipDpiRouteAvailabilityState.toThemeRole(): RipDpiRouteAvailabilityStateRole =
    when (this) {
        RipDpiRouteAvailabilityState.Available -> RipDpiRouteAvailabilityStateRole.Available
        RipDpiRouteAvailabilityState.Selected -> RipDpiRouteAvailabilityStateRole.Selected
        RipDpiRouteAvailabilityState.Configured -> RipDpiRouteAvailabilityStateRole.Configured
        RipDpiRouteAvailabilityState.NeedsSetup -> RipDpiRouteAvailabilityStateRole.NeedsSetup
        RipDpiRouteAvailabilityState.Restricted -> RipDpiRouteAvailabilityStateRole.Restricted
        RipDpiRouteAvailabilityState.Active -> RipDpiRouteAvailabilityStateRole.Active
        RipDpiRouteAvailabilityState.Degraded -> RipDpiRouteAvailabilityStateRole.Degraded
        RipDpiRouteAvailabilityState.Failed -> RipDpiRouteAvailabilityStateRole.Failed
    }

@Composable
private fun RipDpiRouteAvailabilityState.label(): String =
    stringResource(
        when (this) {
            RipDpiRouteAvailabilityState.Available -> R.string.route_state_available
            RipDpiRouteAvailabilityState.Selected -> R.string.route_state_selected
            RipDpiRouteAvailabilityState.Configured -> R.string.route_state_configured
            RipDpiRouteAvailabilityState.NeedsSetup -> R.string.route_state_needs_setup
            RipDpiRouteAvailabilityState.Restricted -> R.string.route_state_restricted
            RipDpiRouteAvailabilityState.Active -> R.string.route_state_active
            RipDpiRouteAvailabilityState.Degraded -> R.string.route_state_degraded
            RipDpiRouteAvailabilityState.Failed -> R.string.route_state_failed
        },
    )

@Composable
private fun RipDpiRouteStackUiState.accessibilityDescription(): String {
    val statusFailed = stringResource(R.string.route_stack_status_failed)
    val statusWarning = stringResource(R.string.route_stack_status_warning)
    val statusActive = stringResource(R.string.route_stack_status_active)
    val parts =
        nodes.map { node ->
            val status =
                when (node.id) {
                    failedNodeId -> statusFailed
                    warningNodeId -> statusWarning
                    activeNodeId -> statusActive
                    else -> node.state.label().lowercase()
                }
            "${node.label} $status"
        }
    val separator = stringResource(R.string.route_stack_node_separator)
    return stringResource(R.string.route_stack_accessibility, parts.joinToString(separator))
}

private val RouteGlyphSize = 40.dp
private val RoutePillIconGap = 4.dp
private val RouteConnectorHeight = 2.dp
private val RouteStackNodeMinWidth = 48.dp
private val RouteStackNodeMaxWidth = 74.dp
private const val RouteDisabledAlpha = 0.72f

@Preview(showBackground = true)
@Composable
private fun RipDpiRouteComponentsPreview() {
    RipDpiComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RipDpiRouteProfileCard(state = sampleRouteProfile(active = true), onClick = {})
            RipDpiRouteStackDiagram(state = sampleRouteStack())
            RipDpiRouteOpportunityPanel(
                title = "Provider route ready",
                message = "This profile can provide VPN-style protection after credentials are configured.",
                state = RipDpiRouteAvailabilityState.NeedsSetup,
                actionLabel = "Setup",
                onAction = {},
            )
        }
    }
}

private fun sampleRouteProfile(active: Boolean = false): RipDpiRouteProfileUiState =
    RipDpiRouteProfileUiState(
        id = "local-vpn",
        title = "Local VPN route",
        subtitle = "Routes selected traffic through the Android VPN tunnel with DNS protection.",
        transportLabel = "Local VPN",
        providerLabel = "RIPDPI",
        capabilities =
            persistentListOf(
                RipDpiRouteCapabilityUiState(RipDpiRouteCapabilityKind.VpnPrivacy, "VPN"),
                RipDpiRouteCapabilityUiState(RipDpiRouteCapabilityKind.DnsProtection, "DNS"),
                RipDpiRouteCapabilityUiState(RipDpiRouteCapabilityKind.AntiDpi, "Anti-DPI"),
            ),
        state = if (active) RipDpiRouteAvailabilityState.Active else RipDpiRouteAvailabilityState.Available,
        isSelected = active,
        isActive = active,
    )

private fun sampleRouteStack(): RipDpiRouteStackUiState =
    RipDpiRouteStackUiState(
        nodes =
            persistentListOf(
                RipDpiRouteStackNodeUiState("device", "Device", RipDpiRouteTransportKind.LocalVpn),
                RipDpiRouteStackNodeUiState("dns", "DNS", RipDpiRouteTransportKind.Warp),
                RipDpiRouteStackNodeUiState("relay", "Relay", RipDpiRouteTransportKind.Relay),
                RipDpiRouteStackNodeUiState("internet", "Internet", RipDpiRouteTransportKind.ExternalVpn),
            ),
        activeNodeId = "relay",
        warningNodeId = "dns",
    )
