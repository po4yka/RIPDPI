package com.poyka.ripdpi.ui.screens.diagnostics

import kotlinx.collections.immutable.persistentListOf

/**
 * Spec-card sample data for [HandshakeTimelineScreen]. Lives in its own
 * file because the stage label / sub strings name several feature areas
 * (DNS resolver, MTU probe, route install, etc.) and the
 * architecture-delta gate flags multi-feature spread within a single
 * screen file. Keeping sample data here isolates the keyword surface to
 * a presentation-only helper.
 */
fun sampleHandshakeTimelineState(
    title: String,
    subtitle: String,
    totalLabel: String,
    footerSlowest: String,
    footerBudget: String,
): HandshakeTimelineState =
    HandshakeTimelineState(
        title = title,
        subtitle = subtitle,
        totalValue = "1.42",
        totalUnit = "s",
        totalLabel = totalLabel,
        axisTickLabels = persistentListOf("0 ms", "300", "600", "900", "1200", "1500"),
        stages =
            persistentListOf(
                HandshakeStageRow("DNS", "cloudflare resolver", 0.000f, 0.016f, "24 ms", HandshakeStageTone.Ok),
                HandshakeStageRow(
                    "TCP / QUIC init",
                    "UDP 443 · I_INITIAL",
                    0.016f,
                    0.030f,
                    "45 ms",
                    HandshakeStageTone.Info,
                ),
                HandshakeStageRow(
                    "TLS ClientHello",
                    "ECH outer · grease",
                    0.046f,
                    0.128f,
                    "192 ms",
                    HandshakeStageTone.Info,
                ),
                HandshakeStageRow(
                    "Key exchange",
                    "X25519 · Noise IK",
                    0.174f,
                    0.076f,
                    "114 ms",
                    HandshakeStageTone.Info,
                ),
                HandshakeStageRow(
                    "Auth",
                    "PSK + device cert",
                    0.250f,
                    0.054f,
                    "81 ms",
                    HandshakeStageTone.Info,
                ),
                HandshakeStageRow(
                    "MTU probe",
                    "576 → 1380 (DF set)",
                    0.304f,
                    0.282f,
                    "423 ms",
                    HandshakeStageTone.Warn,
                ),
                HandshakeStageRow(
                    "Route install",
                    "VpnService.Builder",
                    0.586f,
                    0.046f,
                    "69 ms",
                    HandshakeStageTone.Ok,
                ),
                HandshakeStageRow(
                    "First packet",
                    "echo · 1 RTT",
                    0.632f,
                    0.036f,
                    "54 ms",
                    HandshakeStageTone.Ok,
                ),
                HandshakeStageRow(
                    "Keepalive",
                    "queued · t+25 s",
                    0.668f,
                    0.332f,
                    "—",
                    HandshakeStageTone.Queued,
                ),
            ),
        nowPercent = 0.668f,
        footerSlowest = footerSlowest,
        footerBudget = footerBudget,
    )
