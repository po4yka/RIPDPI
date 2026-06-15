package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

/** Semantic identity of each VPN connection node in the state graph. */
enum class VpnNodeState {
    Disconnected,
    Permissioning,
    Connecting,
    Tunneling,
    Reconnecting,
    Failed,
    Degraded,
}

/**
 * Presentation state for [StateMachineScreen].
 *
 * @param currentState The node that is currently active (ringed in the diagram).
 * @param currentStateLabel Formatted current-state name (e.g. "Tunneling").
 * @param transitionCountLabel Formatted 24 h transition count (e.g. "9 transitions / 24 h").
 * @param disconnectedMetaLabel Sub-label for Disconnected node (e.g. "idle").
 * @param permissioningMetaLabel Sub-label for Permissioning node (e.g. "os prompt").
 * @param connectingMetaLabel Sub-label for Connecting node (e.g. "handshake").
 * @param tunnelingMetaLabel Sub-label for Tunneling node (e.g. "12 m 14 s").
 * @param reconnectingMetaLabel Sub-label for Reconnecting node (e.g. "backoff").
 * @param failedMetaLabel Sub-label for Failed node (e.g. "last 6 h: 0").
 * @param degradedMetaLabel Sub-label for Degraded node (e.g. "2 in 24 h").
 */
data class StateMachineState(
    val currentState: VpnNodeState,
    val currentStateLabel: String,
    val transitionCountLabel: String,
    val disconnectedMetaLabel: String,
    val permissioningMetaLabel: String,
    val connectingMetaLabel: String,
    val tunnelingMetaLabel: String,
    val reconnectingMetaLabel: String,
    val failedMetaLabel: String,
    val degradedMetaLabel: String,
)

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

/**
 * Presentation-only connection state-machine diagram matching
 * `docs/design/rds/preview/vpn-state-machine.html`.
 *
 * Renders 7 VPN-state nodes on a 640 × 380 spec canvas (scaled to fill
 * available width) with directed edges drawn by Compose [Canvas]. The
 * active node is ringed; the active outgoing edge is dashed and accented.
 * A colour-coded legend row sits beneath the diagram. All colours come from
 * [RipDpiThemeTokens] and no animation-spec constants appear here. The small
 * set of Canvas geometry sizes (node corner, status dot, ring inset, legend
 * swatch) use off-scale `.dp` values that have no matching RDS token and are
 * intentionally local to this diagram rather than promoted to `ui/theme/`.
 */
@Composable
fun StateMachineScreen(
    state: StateMachineState,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard(modifier = modifier.fillMaxWidth(), variant = RipDpiCardVariant.Outlined) {
        StateMachineHeader(state)
        Spacer(modifier = Modifier.height(spacing.sm))
        StateMachineDiagram(state)
        Spacer(modifier = Modifier.height(spacing.sm))
        StateMachineLegend()
    }
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

@Composable
private fun StateMachineHeader(state: StateMachineState) {
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.vpn_state_machine_title),
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(
                text = stringResource(R.string.vpn_state_machine_current_prefix),
                style = type.monoSmall,
                color = colors.mutedForeground,
            )
            Text(text = "·", style = type.monoSmall, color = colors.mutedForeground)
            Text(
                text = state.currentStateLabel,
                style = type.monoSmall,
                color = colors.foreground,
            )
            Text(text = "·", style = type.monoSmall, color = colors.mutedForeground)
            Text(
                text = state.transitionCountLabel,
                style = type.monoSmall,
                color = colors.mutedForeground,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Diagram
// ---------------------------------------------------------------------------

// Spec canvas dimensions — all node positions are expressed in this space.
private const val CanvasW = 640f
private const val CanvasH = 380f

// Node half-sizes in spec units (node is 120 × 52).
private const val NodeHalfW = 60f
private const val NodeHalfH = 26f

// Arrow-head geometry multipliers (stroke-width proportional).
private const val ArrowHeadCoerceMin = 0.001f
private const val ArrowHeadLengthFactor = 4.5f
private const val ArrowHeadWidthFactor = 1.8f

// Dash pattern multipliers for active edges (dash len = stroke * 3, gap = stroke * 2.25).
private const val EdgeDashLengthFactor = 3f
private const val EdgeDashGapFactor = 2.25f

// Edge endpoint Y-offsets from node centre (node bottom = cy + NodeHalfH).
// Bottom exit Y for Connecting / Permissioning-row nodes (cy=80, bottom=106).
private const val NodeBottomConnecting = 106f

// Bezier control-point Y for Connecting→Tunneling arc.
private const val EdgeConnectTunnelCpY = 165f

// Tunneling node T-edge arrival point (cx=494, y=184 — slightly above node centre).
private const val EdgeConnectTunnelX1 = 494f
private const val EdgeConnectTunnelY1 = 184f

// Bottom exit Y for Reconnecting / second-row nodes (cy=210, bottom=236).
private const val NodeBottomRow2 = 236f

// Top entry Y for Failed / third-row nodes (cy=320, top=294).
private const val NodeTopRow3 = 294f

// Connecting→Failed arc midpoint control.
private const val EdgeConnectFailedCpX = 360f
private const val EdgeConnectFailedCpY = 200f
private const val EdgeConnectFailedX0 = 520f
private const val EdgeConnectFailedX1 = 212f

// Degraded←→Tunneling vertical edge X offsets (Tunneling cx=470, left=440, right=500).
private const val EdgeTunnelDegradedLeftX = 440f
private const val EdgeTunnelDegradedRightX = 500f

// Degraded.L → Reconnecting.R arc control points.
private const val EdgeDegradedReconnectX0 = 410f
private const val EdgeDegradedReconnectCpX = 320f
private const val EdgeDegradedReconnectCpY = 270f
private const val EdgeDegradedReconnectX1 = 234f
private const val EdgeDegradedReconnectY1 = 214f

// Reconnecting.R → Tunneling.L straight edge (Reconnecting cx=170, right=230; Tunneling cx=470, left=406+4 gap).
private const val EdgeReconnectTunnelX0 = 230f
private const val EdgeReconnectTunnelY = 205f
private const val EdgeReconnectTunnelX1 = 406f

// Failed.L → Disconnected.L cubic left sweep control points.
private const val EdgeFailedDiscX0 = 110f
private const val EdgeFailedDiscCp1X = 20f
private const val EdgeFailedDiscCp2X = 10f
private const val EdgeFailedDiscCp2Y = 84f
private const val EdgeFailedDiscX1 = 16f

// Edge endpoint gap (4 dp inset so arrow tip clears node border).
private const val EdgeNodeGap = 4f

// Node-ring inset for the active-state highlight canvas (negative = expand outward).
private const val NodeRingInset = -4f

// Active-state ring stroke addend (base = RipDpiStroke.Thin; +0.5 makes it stand out).
private const val NodeRingStrokeAddend = 0.5f

// NodeSpecs list indices — named so that the index literals in StateMachineEdgeCanvas
// are not treated as magic numbers.
private const val NodeIndexDisconnected = 0
private const val NodeIndexPermissioning = 1
private const val NodeIndexConnecting = 2
private const val NodeIndexReconnecting = 4
private const val NodeIndexDegraded = 6

// Node centres in spec coordinates (cx, cy).
private data class NodeSpec(
    val state: VpnNodeState,
    val cx: Float,
    val cy: Float,
    val kind: NodeKind,
)

private val NodeSpecs =
    listOf(
        NodeSpec(VpnNodeState.Disconnected, 80f, 80f, NodeKind.Idle),
        NodeSpec(VpnNodeState.Permissioning, 320f, 80f, NodeKind.Transition),
        NodeSpec(VpnNodeState.Connecting, 550f, 80f, NodeKind.Transition),
        NodeSpec(VpnNodeState.Tunneling, 470f, 210f, NodeKind.Healthy),
        NodeSpec(VpnNodeState.Reconnecting, 170f, 210f, NodeKind.Transition),
        NodeSpec(VpnNodeState.Failed, 170f, 320f, NodeKind.Failed),
        NodeSpec(VpnNodeState.Degraded, 470f, 320f, NodeKind.Degraded),
    )

@Composable
private fun StateMachineDiagram(state: StateMachineState) {
    val spacing = RipDpiThemeTokens.spacing
    val diagramHeight: Dp =
        spacing.section + spacing.xxxl + spacing.xxl +
            spacing.xl + spacing.lg + spacing.sm + spacing.xs
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().height(diagramHeight),
    ) {
        val scaleX = constraints.maxWidth.toFloat() / CanvasW
        val scaleY = constraints.maxHeight.toFloat() / CanvasH
        StateMachineEdgeCanvas(state = state, scaleX = scaleX, scaleY = scaleY, modifier = Modifier.matchParentSize())
        StateMachineNodeLayer(state = state, scaleX = scaleX, scaleY = scaleY)
    }
}

private fun DrawScope.smArrowHead(
    endX: Float,
    endY: Float,
    tanX: Float,
    tanY: Float,
    color: Color,
    sw: Float,
) {
    val len = sqrt(tanX * tanX + tanY * tanY).coerceAtLeast(ArrowHeadCoerceMin)
    val ux = tanX / len
    val uy = tanY / len
    val tl = sw * ArrowHeadLengthFactor
    val tw = sw * ArrowHeadWidthFactor
    drawPath(
        Path().apply {
            moveTo(endX, endY)
            lineTo(endX - ux * tl - uy * tw, endY - uy * tl + ux * tw)
            lineTo(endX - ux * tl + uy * tw, endY - uy * tl - ux * tw)
            close()
        },
        color,
    )
}

private fun DrawScope.smLineEdge(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    color: Color,
    thin: Float,
    thick: Float,
    active: Boolean = false,
) {
    val sw = if (active) thick else thin
    val effect =
        if (active) {
            PathEffect.dashPathEffect(floatArrayOf(sw * EdgeDashLengthFactor, sw * EdgeDashGapFactor))
        } else {
            null
        }
    drawLine(color = color, start = Offset(x0, y0), end = Offset(x1, y1), strokeWidth = sw, pathEffect = effect)
    smArrowHead(x1, y1, x1 - x0, y1 - y0, color, sw)
}

private fun DrawScope.smQuadEdge(
    x0: Float,
    y0: Float,
    cpx: Float,
    cpy: Float,
    x1: Float,
    y1: Float,
    color: Color,
    thin: Float,
    thick: Float,
    active: Boolean = false,
) {
    val sw = if (active) thick else thin
    val effect =
        if (active) {
            PathEffect.dashPathEffect(floatArrayOf(sw * EdgeDashLengthFactor, sw * EdgeDashGapFactor))
        } else {
            null
        }
    val path =
        Path().apply {
            moveTo(x0, y0)
            quadraticTo(cpx, cpy, x1, y1)
        }
    drawPath(path, color, style = Stroke(width = sw, pathEffect = effect))
    smArrowHead(x1, y1, x1 - cpx, y1 - cpy, color, sw)
}

private fun DrawScope.smCubicEdge(
    x0: Float,
    y0: Float,
    cp1x: Float,
    cp1y: Float,
    cp2x: Float,
    cp2y: Float,
    x1: Float,
    y1: Float,
    color: Color,
    thin: Float,
) {
    val path =
        Path().apply {
            moveTo(x0, y0)
            cubicTo(cp1x, cp1y, cp2x, cp2y, x1, y1)
        }
    drawPath(path, color, style = Stroke(width = thin))
    smArrowHead(x1, y1, x1 - cp2x, y1 - cp2y, color, thin)
}

// Edges 1–4: top row (Disconnected → Permissioning → Connecting → Tunneling / Failed).
private fun DrawScope.smTopRowEdges(
    scaleX: Float,
    scaleY: Float,
    disconnectedCx: Float,
    permissioningCx: Float,
    connectingCx: Float,
    topRowY: Float,
    edgeMuted: Color,
    edgeActive: Color,
    edgeFail: Color,
    thin: Float,
    thick: Float,
    isTunneling: Boolean,
) {
    fun sx(x: Float) = x * scaleX

    fun sy(y: Float) = y * scaleY
    val halfW = sx(NodeHalfW)
    val gap = sx(EdgeNodeGap)
    // 1. Disconnected.R → Permissioning.L
    smLineEdge(disconnectedCx + halfW, topRowY, permissioningCx - halfW - gap, topRowY, edgeMuted, thin, thick)
    // 2. Permissioning.R → Connecting.L
    smLineEdge(permissioningCx + halfW, topRowY, connectingCx - halfW - gap, topRowY, edgeMuted, thin, thick)
    // 3. Connecting.B → Tunneling.T
    smQuadEdge(
        connectingCx,
        sy(NodeBottomConnecting),
        connectingCx,
        sy(EdgeConnectTunnelCpY),
        sx(EdgeConnectTunnelX1),
        sy(EdgeConnectTunnelY1),
        if (isTunneling) edgeActive else edgeMuted,
        thin,
        thick,
        isTunneling,
    )
    // 4. Connecting.B → Failed.T
    smQuadEdge(
        sx(EdgeConnectFailedX0),
        sy(NodeBottomConnecting),
        sx(EdgeConnectFailedCpX),
        sy(EdgeConnectFailedCpY),
        sx(EdgeConnectFailedX1),
        sy(NodeTopRow3),
        edgeFail,
        thin,
        thick,
    )
}

// Edges 5–10: lower rows (Tunneling ↔ Degraded, Degraded → Reconnecting,
// Reconnecting → Tunneling / Failed, Failed → Disconnected).
private fun DrawScope.smLowerRowEdges(
    scaleX: Float,
    scaleY: Float,
    reconnectingCx: Float,
    degradedCy: Float,
    edgeMuted: Color,
    edgeFail: Color,
    thin: Float,
    thick: Float,
) {
    fun sx(x: Float) = x * scaleX

    fun sy(y: Float) = y * scaleY
    val leftX = sx(EdgeTunnelDegradedLeftX)
    val rightX = sx(EdgeTunnelDegradedRightX)
    val row2Bottom = sy(NodeBottomRow2)
    val row3Top = sy(NodeTopRow3)
    // 5. Tunneling.B → Degraded.T [left]
    smLineEdge(leftX, row2Bottom, leftX, row3Top, edgeMuted, thin, thick)
    // 6. Degraded.T → Tunneling.B [right]
    smLineEdge(rightX, row3Top, rightX, row2Bottom, edgeMuted, thin, thick)
    // 7. Degraded.L → Reconnecting.R
    smQuadEdge(
        sx(EdgeDegradedReconnectX0),
        degradedCy,
        sx(EdgeDegradedReconnectCpX),
        sy(EdgeDegradedReconnectCpY),
        sx(EdgeDegradedReconnectX1),
        sy(EdgeDegradedReconnectY1),
        edgeMuted,
        thin,
        thick,
    )
    // 8. Reconnecting.R → Tunneling.L
    smLineEdge(
        sx(EdgeReconnectTunnelX0),
        sy(EdgeReconnectTunnelY),
        sx(EdgeReconnectTunnelX1),
        sy(EdgeReconnectTunnelY),
        edgeMuted,
        thin,
        thick,
    )
    // 9. Reconnecting.B → Failed.T
    smLineEdge(reconnectingCx, sy(NodeBottomRow2), reconnectingCx, sy(NodeTopRow3), edgeFail, thin, thick)
    // 10. Failed.L → Disconnected.L
    smCubicEdge(
        sx(EdgeFailedDiscX0),
        degradedCy,
        sx(EdgeFailedDiscCp1X),
        degradedCy,
        sx(EdgeFailedDiscCp2X),
        sy(EdgeFailedDiscCp2Y),
        sx(EdgeFailedDiscX1),
        sy(EdgeFailedDiscCp2Y),
        edgeMuted,
        thin,
    )
}

@Composable
private fun StateMachineEdgeCanvas(
    state: StateMachineState,
    scaleX: Float,
    scaleY: Float,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    val colors = RipDpiThemeTokens.colors
    val edgeMuted = colors.outlineVariant
    val edgeActive = colors.foreground
    val edgeFail = colors.destructive
    val isTunneling = state.currentState == VpnNodeState.Tunneling

    Canvas(modifier = modifier) {
        val thin = RipDpiStroke.Thin.toPx()
        val thick = RipDpiStroke.Thick.toPx()
        val disconnectedCx = NodeSpecs[NodeIndexDisconnected].cx * scaleX
        val permissioningCx = NodeSpecs[NodeIndexPermissioning].cx * scaleX
        val connectingCx = NodeSpecs[NodeIndexConnecting].cx * scaleX
        val reconnectingCx = NodeSpecs[NodeIndexReconnecting].cx * scaleX
        val topRowY = NodeSpecs[NodeIndexDisconnected].cy * scaleY
        val degradedCy = NodeSpecs[NodeIndexDegraded].cy * scaleY

        smTopRowEdges(
            scaleX,
            scaleY,
            disconnectedCx,
            permissioningCx,
            connectingCx,
            topRowY,
            edgeMuted,
            edgeActive,
            edgeFail,
            thin,
            thick,
            isTunneling,
        )
        smLowerRowEdges(scaleX, scaleY, reconnectingCx, degradedCy, edgeMuted, edgeFail, thin, thick)
    }
}

@Composable
private fun StateMachineNodeLayer(
    state: StateMachineState,
    scaleX: Float,
    scaleY: Float,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val nodeWDp = with(density) { (NodeHalfW * 2f * scaleX).toDp() }
    val nodeHDp = with(density) { (NodeHalfH * 2f * scaleY).toDp() }

    val nodeLabels =
        mapOf(
            VpnNodeState.Disconnected to stringResource(R.string.vpn_state_machine_node_disconnected),
            VpnNodeState.Permissioning to stringResource(R.string.vpn_state_machine_node_permissioning),
            VpnNodeState.Connecting to stringResource(R.string.vpn_state_machine_node_connecting),
            VpnNodeState.Tunneling to stringResource(R.string.vpn_state_machine_node_tunneling),
            VpnNodeState.Reconnecting to stringResource(R.string.vpn_state_machine_node_reconnecting),
            VpnNodeState.Failed to stringResource(R.string.vpn_state_machine_node_failed),
            VpnNodeState.Degraded to stringResource(R.string.vpn_state_machine_node_degraded),
        )
    val nodeMetas =
        mapOf(
            VpnNodeState.Disconnected to state.disconnectedMetaLabel,
            VpnNodeState.Permissioning to state.permissioningMetaLabel,
            VpnNodeState.Connecting to state.connectingMetaLabel,
            VpnNodeState.Tunneling to state.tunnelingMetaLabel,
            VpnNodeState.Reconnecting to state.reconnectingMetaLabel,
            VpnNodeState.Failed to state.failedMetaLabel,
            VpnNodeState.Degraded to state.degradedMetaLabel,
        )

    NodeSpecs.forEach { spec ->
        val leftDp = with(density) { ((spec.cx - NodeHalfW) * scaleX).toDp() }
        val topDp = with(density) { ((spec.cy - NodeHalfH) * scaleY).toDp() }
        NodeCard(
            modifier = Modifier.offset(x = leftDp, y = topDp).size(width = nodeWDp, height = nodeHDp),
            label = nodeLabels[spec.state] ?: "",
            metaLabel = nodeMetas[spec.state] ?: "",
            nodeKind = spec.kind,
            isCurrent = spec.state == state.currentState,
        )
    }
}

// ---------------------------------------------------------------------------
// Node card
// ---------------------------------------------------------------------------

/** Semantic category that drives the node's colour scheme. */
private enum class NodeKind { Idle, Transition, Healthy, Degraded, Failed }

private val NodeCorner = 10.dp
private val DotSize = 6.dp

private data class NodeCardColors(
    val container: Color,
    val border: Color,
    val label: Color,
    val dot: Color,
)

@Composable
private fun nodeCardColors(nodeKind: NodeKind): NodeCardColors {
    val colors = RipDpiThemeTokens.colors
    return when (nodeKind) {
        NodeKind.Idle -> {
            NodeCardColors(colors.card, colors.border, colors.foreground, colors.mutedForeground)
        }

        NodeKind.Transition -> {
            NodeCardColors(colors.card, colors.info, colors.foreground, colors.info)
        }

        NodeKind.Healthy -> {
            NodeCardColors(colors.success.copy(alpha = 0.12f), colors.success, colors.success, colors.success)
        }

        NodeKind.Degraded -> {
            NodeCardColors(colors.warningContainer, colors.warning, colors.warningContainerForeground, colors.warning)
        }

        NodeKind.Failed -> {
            NodeCardColors(
                colors.destructiveContainer,
                colors.destructive,
                colors.destructiveContainerForeground,
                colors.destructive,
            )
        }
    }
}

@Composable
private fun NodeCard(
    modifier: Modifier,
    label: String,
    metaLabel: String,
    nodeKind: NodeKind,
    isCurrent: Boolean,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    val nc = nodeCardColors(nodeKind)

    Box(modifier = modifier) {
        // Current-state ring — drawn as a Canvas overlay outside the Surface.
        if (isCurrent) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val inset = NodeRingInset.dp.toPx()
                val corner = NodeCorner.toPx() - inset
                drawRoundRect(
                    color = colors.foreground,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - 2f * inset, size.height - 2f * inset),
                    cornerRadius = CornerRadius(corner),
                    style = Stroke(width = RipDpiStroke.Thin.toPx() + NodeRingStrokeAddend),
                )
            }
        }

        Surface(
            modifier = Modifier.matchParentSize(),
            shape = RoundedCornerShape(NodeCorner),
            color = nc.container,
            contentColor = nc.label,
            border = BorderStroke(RipDpiStroke.Thin, nc.border),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(spacing.xs)) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(text = label, style = type.monoSmall, color = nc.label, maxLines = 1)
                    Text(text = metaLabel, style = type.caption, color = colors.mutedForeground, maxLines = 1)
                }
            }
        }

        // Status dot — top-right corner of the Box.
        Canvas(modifier = Modifier.align(Alignment.TopEnd).size(DotSize)) {
            drawCircle(color = nc.dot)
        }
    }
}

// ---------------------------------------------------------------------------
// Legend
// ---------------------------------------------------------------------------

@Composable
private fun StateMachineLegend() {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        LegendSwatch(
            color = colors.card,
            borderColor = colors.border,
            label = stringResource(R.string.vpn_state_machine_legend_idle),
        )
        LegendSwatch(
            color = colors.infoContainer,
            borderColor = colors.info,
            label = stringResource(R.string.vpn_state_machine_legend_transition),
        )
        LegendSwatch(
            color = colors.success.copy(alpha = 0.12f),
            borderColor = colors.success,
            label = stringResource(R.string.vpn_state_machine_legend_healthy),
        )
        LegendSwatch(
            color = colors.warningContainer,
            borderColor = colors.warning,
            label = stringResource(R.string.vpn_state_machine_legend_degraded),
        )
        LegendSwatch(
            color = colors.destructiveContainer,
            borderColor = colors.destructive,
            label = stringResource(R.string.vpn_state_machine_legend_failed),
        )
    }
}

@Composable
private fun LegendSwatch(
    color: Color,
    borderColor: Color,
    label: String,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
    ) {
        Canvas(modifier = Modifier.size(10.dp)) {
            drawRoundRect(
                color = color,
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
            drawRoundRect(
                color = borderColor,
                cornerRadius = CornerRadius(3.dp.toPx()),
                style = Stroke(width = RipDpiStroke.Thin.toPx()),
            )
        }
        Text(
            text = label,
            style = type.monoSmall,
            color = colors.mutedForeground,
        )
    }
}
