package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.navigation.RipDpiTopAppBar
import com.poyka.ripdpi.ui.components.scaffold.RipDpiScreenScaffold
import com.poyka.ripdpi.ui.theme.RipDpiExtendedColors
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

enum class PcapPacketProtocol { Tcp, Udp, Tls, Other }

data class PcapPacket(
    val index: Int,
    val timeLabel: String,
    val src: String,
    val dst: String,
    val protocol: PcapPacketProtocol,
    val summary: String,
    val hexDump: String,
)

private const val WEIGHT_NO = 0.7f
private const val WEIGHT_TIME = 1f
private const val WEIGHT_SRC = 1.3f
private const val WEIGHT_DST = 1.3f
private const val WEIGHT_SUMMARY = 1.5f
private const val SELECTED_ROW_ALPHA = 0.2f
private const val PROTOCOL_BADGE_ALPHA = 0.2f

@Composable
fun PcapViewerScreen(
    fileName: String,
    packetCount: Int,
    packets: ImmutableList<PcapPacket>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initiallySelectedIndex: Int = 0,
) {
    val colors = RipDpiThemeTokens.colors
    val layout = RipDpiThemeTokens.layout
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    var selectedIndex by rememberSaveable { mutableIntStateOf(initiallySelectedIndex) }
    val listState = rememberLazyListState()

    RipDpiScreenScaffold(
        modifier = modifier,
        topBar = {
            RipDpiTopAppBar(
                title = stringResource(R.string.title_pcap_viewer),
                navigationIcon = RipDpiIcons.Back,
                onNavigationClick = onBack,
            )
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(colors.background),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .widthIn(max = layout.contentMaxWidth),
                state = listState,
                contentPadding =
                    PaddingValues(
                        start = layout.horizontalPadding,
                        top = spacing.sm,
                        end = layout.horizontalPadding,
                        bottom = spacing.xxl,
                    ),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                item(key = "pcap-header") {
                    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
                        Text(
                            text = fileName,
                            style = type.bodyEmphasis,
                            color = colors.foreground,
                        )
                        Text(
                            text =
                                stringResource(
                                    R.string.vpn_pcap_viewer_meta_format,
                                    packetCount,
                                ),
                            style = type.caption,
                            color = colors.mutedForeground,
                        )
                    }
                }
                item(key = "pcap-column-headers") {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = spacing.sm, vertical = spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        HeaderCell(
                            text = stringResource(R.string.vpn_pcap_viewer_col_no),
                            modifier = Modifier.weight(WEIGHT_NO),
                        )
                        HeaderCell(
                            text = stringResource(R.string.vpn_pcap_viewer_col_time),
                            modifier = Modifier.weight(WEIGHT_TIME),
                        )
                        HeaderCell(
                            text = stringResource(R.string.vpn_pcap_viewer_col_src),
                            modifier = Modifier.weight(WEIGHT_SRC),
                        )
                        HeaderCell(
                            text = stringResource(R.string.vpn_pcap_viewer_col_dst),
                            modifier = Modifier.weight(WEIGHT_DST),
                        )
                        HeaderCell(
                            text = stringResource(R.string.vpn_pcap_viewer_col_summary),
                            modifier = Modifier.weight(WEIGHT_SUMMARY),
                        )
                    }
                }
                items(packets, key = { it.index }) { packet ->
                    Column {
                        PcapPacketRow(
                            packet = packet,
                            isSelected = packet.index == selectedIndex,
                            onClick = { selectedIndex = packet.index },
                        )
                        if (packet.index == selectedIndex) {
                            RipDpiCard(
                                variant = RipDpiCardVariant.Tonal,
                                paddingValues = PaddingValues(spacing.md),
                            ) {
                                Text(
                                    text = stringResource(R.string.vpn_pcap_viewer_hex_label),
                                    style = type.smallLabel,
                                    color = colors.mutedForeground,
                                )
                                Text(
                                    text = packet.hexDump,
                                    style = type.monoLog,
                                    color = colors.mutedForeground,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = RipDpiThemeTokens.type.smallLabel,
        color = RipDpiThemeTokens.colors.mutedForeground,
        modifier = modifier,
    )
}

@Composable
private fun PcapPacketRow(
    packet: PcapPacket,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val shapes = RipDpiThemeTokens.shapes
    val type = RipDpiThemeTokens.type
    val rowBackground =
        if (isSelected) colors.accent.copy(alpha = SELECTED_ROW_ALPHA) else Color.Unspecified

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .background(rowBackground)
                .padding(horizontal = spacing.sm, vertical = spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = packet.index.toString(),
            style = type.monoSmall,
            color = colors.mutedForeground,
            modifier = Modifier.weight(WEIGHT_NO),
        )
        Text(
            text = packet.timeLabel,
            style = type.monoSmall,
            color = colors.mutedForeground,
            modifier = Modifier.weight(WEIGHT_TIME),
        )
        Text(
            text = packet.src,
            style = type.monoSmall,
            color = colors.foreground,
            modifier = Modifier.weight(WEIGHT_SRC),
        )
        Text(
            text = packet.dst,
            style = type.monoSmall,
            color = colors.foreground,
            modifier = Modifier.weight(WEIGHT_DST),
        )
        Row(
            modifier = Modifier.weight(WEIGHT_SUMMARY),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Surface(
                shape = shapes.sm,
                color = protocolColor(packet.protocol, colors).copy(alpha = PROTOCOL_BADGE_ALPHA),
                contentColor = colors.foreground,
            ) {
                Text(
                    text = protocolLabel(packet.protocol),
                    style = type.monoSmall,
                    color = protocolColor(packet.protocol, colors),
                    modifier = Modifier.padding(horizontal = spacing.xs),
                )
            }
            Text(
                text = packet.summary,
                style = type.monoSmall,
                color = colors.foreground,
            )
        }
    }
}

private fun protocolColor(
    protocol: PcapPacketProtocol,
    colors: RipDpiExtendedColors,
): Color =
    when (protocol) {
        PcapPacketProtocol.Tcp -> colors.info
        PcapPacketProtocol.Udp -> colors.success
        PcapPacketProtocol.Tls -> colors.warning
        PcapPacketProtocol.Other -> colors.mutedForeground
    }

private fun protocolLabel(protocol: PcapPacketProtocol): String =
    when (protocol) {
        PcapPacketProtocol.Tcp -> "TCP"
        PcapPacketProtocol.Udp -> "UDP"
        PcapPacketProtocol.Tls -> "TLS"
        PcapPacketProtocol.Other -> "OTH"
    }

private val sampleHexDump =
    """
    0000  45 00 02 1c 1a 2b 40 00  40 06 7c 35 0a 00 00 02
    0010  01 01 01 01 d4 31 01 bb  9f 8a 1c 22 00 00 00 00
    0020  a0 02 ff ff e8 4a 00 00  02 04 05 b4 04 02 08 0a
    0030  00 00 00 00 00 00 00 00  01 03 03 07
    """.trimIndent()

private val samplePackets =
    persistentListOf(
        PcapPacket(
            index = 1,
            timeLabel = "0.000 423",
            src = "10.0.0.2:54321",
            dst = "1.1.1.1:443",
            protocol = PcapPacketProtocol.Tcp,
            summary = "SYN",
            hexDump = sampleHexDump,
        ),
        PcapPacket(
            index = 2,
            timeLabel = "0.012 117",
            src = "1.1.1.1:443",
            dst = "10.0.0.2:54321",
            protocol = PcapPacketProtocol.Tcp,
            summary = "SYN+ACK",
            hexDump = sampleHexDump,
        ),
        PcapPacket(
            index = 3,
            timeLabel = "0.014 902",
            src = "10.0.0.2:54321",
            dst = "1.1.1.1:443",
            protocol = PcapPacketProtocol.Tls,
            summary = "ClientHello · SNI=youtube.com",
            hexDump = sampleHexDump,
        ),
        PcapPacket(
            index = 4,
            timeLabel = "0.029 581",
            src = "1.1.1.1:443",
            dst = "10.0.0.2:54321",
            protocol = PcapPacketProtocol.Tls,
            summary = "ServerHello",
            hexDump = sampleHexDump,
        ),
        PcapPacket(
            index = 5,
            timeLabel = "0.041 002",
            src = "10.0.0.2:55001",
            dst = "8.8.8.8:53",
            protocol = PcapPacketProtocol.Udp,
            summary = "DNS A youtube.com",
            hexDump = sampleHexDump,
        ),
        PcapPacket(
            index = 6,
            timeLabel = "0.055 117",
            src = "8.8.8.8:53",
            dst = "10.0.0.2:55001",
            protocol = PcapPacketProtocol.Udp,
            summary = "DNS reply 142.250.x.x",
            hexDump = sampleHexDump,
        ),
    )

@Preview(showBackground = true)
@Composable
private fun PcapViewerScreenLightPreview() {
    RipDpiComponentPreview {
        PcapViewerScreen(
            fileName = "ripdpi-2026-05-19.pcap",
            packetCount = samplePackets.size,
            packets = samplePackets,
            onBack = {},
            initiallySelectedIndex = 3,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PcapViewerScreenDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        PcapViewerScreen(
            fileName = "ripdpi-2026-05-19.pcap",
            packetCount = samplePackets.size,
            packets = samplePackets,
            onBack = {},
            initiallySelectedIndex = 3,
        )
    }
}
