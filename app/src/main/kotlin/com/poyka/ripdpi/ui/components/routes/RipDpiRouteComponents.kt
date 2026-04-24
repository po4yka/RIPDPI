package com.poyka.ripdpi.ui.components.routes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.components.ripDpiSelectable
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiRouteAvailabilityStateRole
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
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
fun RipDpiRouteProfileCard(
    state: RipDpiRouteProfileUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = state.state != RipDpiRouteAvailabilityState.Restricted,
) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val shape = RipDpiThemeTokens.shapes.xl
    val surface =
        RipDpiThemeTokens.surfaces.resolve(
            RipDpiThemeTokens.surfaceRoles.routes.profile,
        )
    val routeState = RipDpiThemeTokens.state.route.resolve(state.state.toThemeRole())
    val effectiveContainer = if (state.isSelected) routeState.container else surface.container
    val effectiveBorder = if (state.isSelected || state.isActive) routeState.border else surface.border
    val effectiveContent = if (state.isSelected) routeState.content else surface.content

    Column(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.routeProfile(state.id))
                .fillMaxWidth()
                .heightIn(min = components.rows.settingsRowMinHeightWithSubtitle)
                .shadow(surface.shadowElevation, shape, clip = false)
                .clip(shape)
                .background(effectiveContainer, shape)
                .border(
                    width = routeState.borderWidth,
                    color = effectiveBorder,
                    shape = shape,
                ).alpha(if (enabled) routeState.alpha else routeState.alpha * RouteDisabledAlpha)
                .ripDpiSelectable(
                    selected = state.isSelected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    hapticFeedback = RipDpiHapticFeedback.Selection,
                    onClick = onClick,
                ).padding(components.inputs.fieldHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
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
fun RipDpiRouteCapabilityPill(
    capability: RipDpiRouteCapabilityUiState,
    modifier: Modifier = Modifier,
) {
    val components = RipDpiThemeTokens.components
    val shape = RipDpiThemeTokens.shapes.xxl
    val state = RipDpiThemeTokens.state.route.resolve(capability.state.toThemeRole())

    Row(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.routeCapability(capability.kind.stableKey))
                .clip(shape)
                .background(state.badgeContainer, shape)
                .border(RipDpiStroke.Thin, state.badgeBorder, shape)
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
fun RipDpiRouteStackDiagram(
    state: RipDpiRouteStackUiState,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val shape = RipDpiThemeTokens.shapes.lg
    val surface =
        RipDpiThemeTokens.surfaces.resolve(
            RipDpiThemeTokens.surfaceRoles.routes.stack,
        )
    val description = remember(state) { state.accessibilityDescription() }

    Row(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.RouteStack)
                .fillMaxWidth()
                .clip(shape)
                .background(surface.container, shape)
                .border(RipDpiStroke.Thin, surface.border, shape)
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
fun RipDpiRouteOpportunityPanel(
    title: String,
    message: String,
    state: RipDpiRouteAvailabilityState,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val shape = RipDpiThemeTokens.shapes.xl
    val surface =
        RipDpiThemeTokens.surfaces.resolve(
            RipDpiThemeTokens.surfaceRoles.routes.opportunity,
        )
    val routeState = RipDpiThemeTokens.state.route.resolve(state.toThemeRole())
    val actionModifier =
        if (onAction != null) {
            Modifier.ripDpiClickable(onClickLabel = actionLabel, onClick = onAction)
        } else {
            Modifier
        }

    Column(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.RouteOpportunityPanel)
                .fillMaxWidth()
                .clip(shape)
                .background(surface.container, shape)
                .border(RipDpiStroke.Thin, routeState.border, shape)
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

    Box(
        modifier =
            modifier
                .size(RouteGlyphSize)
                .background(style.badgeContainer, CircleShape)
                .border(RipDpiStroke.Thin, style.badgeBorder, CircleShape),
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
    val label = if (active) "Active" else state.label()
    val style =
        RipDpiThemeTokens.state.route.resolve(
            if (active) RipDpiRouteAvailabilityStateRole.Active else state.toThemeRole(),
        )

    Text(
        text = label,
        style = RipDpiThemeTokens.type.smallLabel,
        color = style.badgeContent,
        modifier =
            Modifier
                .clip(RipDpiThemeTokens.shapes.xxl)
                .background(style.badgeContainer, RipDpiThemeTokens.shapes.xxl)
                .border(RipDpiStroke.Thin, style.badgeBorder, RipDpiThemeTokens.shapes.xxl)
                .padding(horizontal = 8.dp, vertical = 2.dp),
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
    val style = RipDpiThemeTokens.state.route.resolve(state.toThemeRole())

    Box(
        modifier =
            modifier
                .height(RouteConnectorHeight)
                .background(style.border, RoundedCornerShape(RouteConnectorHeight)),
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

private fun RipDpiRouteAvailabilityState.label(): String =
    when (this) {
        RipDpiRouteAvailabilityState.Available -> "Available"
        RipDpiRouteAvailabilityState.Selected -> "Selected"
        RipDpiRouteAvailabilityState.Configured -> "Configured"
        RipDpiRouteAvailabilityState.NeedsSetup -> "Setup"
        RipDpiRouteAvailabilityState.Restricted -> "Restricted"
        RipDpiRouteAvailabilityState.Active -> "Active"
        RipDpiRouteAvailabilityState.Degraded -> "Degraded"
        RipDpiRouteAvailabilityState.Failed -> "Failed"
    }

private fun RipDpiRouteStackUiState.accessibilityDescription(): String {
    val parts =
        nodes.map { node ->
            val status =
                when (node.id) {
                    failedNodeId -> "failed"
                    warningNodeId -> "warning"
                    activeNodeId -> "active"
                    else -> node.state.label().lowercase()
                }
            "${node.label} $status"
        }
    return "Secure route stack: ${parts.joinToString(" to ")}"
}

private val RouteGlyphSize = 40.dp
private val RoutePillIconGap = 4.dp
private val RouteConnectorHeight = 2.dp
private val RouteStackNodeMinWidth = 48.dp
private val RouteStackNodeMaxWidth = 74.dp
private const val RouteDisabledAlpha = 0.72f

@Suppress("UnusedPrivateMember")
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
