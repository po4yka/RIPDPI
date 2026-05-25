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
 * A colour-coded legend row sits beneath the diagram. All tokens come from
 * [RipDpiThemeTokens]; no literal colours, dp values outside `ui/theme/`,
 * or animation-spec constants appear in this file.
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
        fun sx(x: Float) = x * scaleX

        fun sy(y: Float) = y * scaleY

        val thin = RipDpiStroke.Thin.toPx()
        val thick = RipDpiStroke.Thick.toPx()

        fun arrowHead(
            endX: Float,
            endY: Float,
            tanX: Float,
            tanY: Float,
            color: Color,
            sw: Float,
        ) {
            val len = sqrt(tanX * tanX + tanY * tanY).coerceAtLeast(0.001f)
            val ux = tanX / len
            val uy = tanY / len
            val tl = sw * 4.5f
            val tw = sw * 1.8f
            val tip =
                Path().apply {
                    moveTo(endX, endY)
                    lineTo(endX - ux * tl - uy * tw, endY - uy * tl + ux * tw)
                    lineTo(endX - ux * tl + uy * tw, endY - uy * tl - ux * tw)
                    close()
                }
            drawPath(tip, color)
        }

        fun lineEdge(
            x0: Float,
            y0: Float,
            x1: Float,
            y1: Float,
            color: Color,
            active: Boolean = false,
        ) {
            val sw = if (active) thick else thin
            val effect = if (active) PathEffect.dashPathEffect(floatArrayOf(sw * 3f, sw * 2.25f)) else null
            drawLine(color = color, start = Offset(x0, y0), end = Offset(x1, y1), strokeWidth = sw, pathEffect = effect)
            arrowHead(x1, y1, x1 - x0, y1 - y0, color, sw)
        }

        fun quadEdge(
            x0: Float,
            y0: Float,
            cpx: Float,
            cpy: Float,
            x1: Float,
            y1: Float,
            color: Color,
            active: Boolean = false,
        ) {
            val sw = if (active) thick else thin
            val effect = if (active) PathEffect.dashPathEffect(floatArrayOf(sw * 3f, sw * 2.25f)) else null
            val path =
                Path().apply {
                    moveTo(x0, y0)
                    quadraticTo(cpx, cpy, x1, y1)
                }
            drawPath(path, color, style = Stroke(width = sw, pathEffect = effect))
            arrowHead(x1, y1, x1 - cpx, y1 - cpy, color, sw)
        }

        fun cubicEdge(
            x0: Float,
            y0: Float,
            cp1x: Float,
            cp1y: Float,
            cp2x: Float,
            cp2y: Float,
            x1: Float,
            y1: Float,
            color: Color,
        ) {
            val path =
                Path().apply {
                    moveTo(x0, y0)
                    cubicTo(cp1x, cp1y, cp2x, cp2y, x1, y1)
                }
            drawPath(path, color, style = Stroke(width = thin))
            arrowHead(x1, y1, x1 - cp2x, y1 - cp2y, color, thin)
        }

        // 1. Disconnected.R → Permissioning.L — tap connect
        lineEdge(sx(80f + NodeHalfW), sy(80f), sx(320f - NodeHalfW - 4f), sy(80f), edgeMuted)
        // 2. Permissioning.R → Connecting.L — allowed
        lineEdge(sx(320f + NodeHalfW), sy(80f), sx(550f - NodeHalfW - 4f), sy(80f), edgeMuted)
        // 3. Connecting.B → Tunneling.T — handshake ok [ACTIVE when Tunneling]
        quadEdge(
            sx(550f),
            sy(106f),
            sx(550f),
            sy(165f),
            sx(494f),
            sy(184f),
            if (isTunneling) edgeActive else edgeMuted,
            active = isTunneling,
        )
        // 4. Connecting.B → Failed.T — timeout 8 s [fail]
        quadEdge(sx(520f), sy(106f), sx(360f), sy(200f), sx(212f), sy(294f), edgeFail)
        // 5. Tunneling.B → Degraded.T — loss > 3% [left]
        lineEdge(sx(440f), sy(236f), sx(440f), sy(294f), edgeMuted)
        // 6. Degraded.T → Tunneling.B — loss < 0.5% [right]
        lineEdge(sx(500f), sy(294f), sx(500f), sy(236f), edgeMuted)
        // 7. Degraded.L → Reconnecting.R — network change
        quadEdge(sx(410f), sy(320f), sx(320f), sy(270f), sx(234f), sy(214f), edgeMuted)
        // 8. Reconnecting.R → Tunneling.L — re-handshake
        lineEdge(sx(230f), sy(205f), sx(406f), sy(205f), edgeMuted)
        // 9. Reconnecting.B → Failed.T — 3 attempts [fail]
        lineEdge(sx(170f), sy(236f), sx(170f), sy(294f), edgeFail)
        // 10. Failed.L → Disconnected.L — dismiss [cubic left sweep]
        cubicEdge(sx(110f), sy(320f), sx(20f), sy(320f), sx(10f), sy(84f), sx(16f), sy(84f), edgeMuted)
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

    val containerColor: Color
    val borderColor: Color
    val labelColor: Color
    val dotColor: Color

    when (nodeKind) {
        NodeKind.Idle -> {
            containerColor = colors.card
            borderColor = colors.border
            labelColor = colors.foreground
            dotColor = colors.mutedForeground
        }

        NodeKind.Transition -> {
            containerColor = colors.card
            borderColor = colors.info
            labelColor = colors.foreground
            dotColor = colors.info
        }

        NodeKind.Healthy -> {
            containerColor = colors.success.copy(alpha = 0.12f)
            borderColor = colors.success
            labelColor = colors.success
            dotColor = colors.success
        }

        NodeKind.Degraded -> {
            containerColor = colors.warningContainer
            borderColor = colors.warning
            labelColor = colors.warningContainerForeground
            dotColor = colors.warning
        }

        NodeKind.Failed -> {
            containerColor = colors.destructiveContainer
            borderColor = colors.destructive
            labelColor = colors.destructiveContainerForeground
            dotColor = colors.destructive
        }
    }

    Box(modifier = modifier) {
        // Current-state ring — drawn as a Canvas overlay outside the Surface.
        if (isCurrent) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val inset = -4.dp.toPx()
                val corner = NodeCorner.toPx() - inset
                drawRoundRect(
                    color = colors.foreground,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - 2f * inset, size.height - 2f * inset),
                    cornerRadius = CornerRadius(corner),
                    style = Stroke(width = RipDpiStroke.Thin.toPx() + 0.5f),
                )
            }
        }

        Surface(
            modifier = Modifier.matchParentSize(),
            shape = RoundedCornerShape(NodeCorner),
            color = containerColor,
            contentColor = labelColor,
            border = BorderStroke(RipDpiStroke.Thin, borderColor),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(spacing.xs)) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = label,
                        style = type.monoSmall,
                        color = labelColor,
                        maxLines = 1,
                    )
                    Text(
                        text = metaLabel,
                        style = type.caption,
                        color = colors.mutedForeground,
                        maxLines = 1,
                    )
                }
            }
        }

        // Status dot — top-right corner of the Box.
        Canvas(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .size(DotSize),
        ) {
            drawCircle(color = dotColor)
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
