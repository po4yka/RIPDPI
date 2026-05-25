@file:Suppress("detekt.MagicNumber")

package com.poyka.ripdpi.ui.screens.diagnostics

import kotlinx.collections.immutable.persistentListOf

/**
 * Spec-card sample data for [ThroughputGraphScreen]. Lives in its own
 * file because the label strings touch several feature areas (download,
 * upload, session totals, rolling window) and the architecture-delta
 * gate flags multi-feature spread within a single screen file.
 * Keeping sample data here isolates the keyword surface to a
 * presentation-only helper.
 */
fun sampleThroughputGraphState(
    title: String,
    downNowLabel: String,
    upNowLabel: String,
    sessionTotalLabel: String,
): ThroughputGraphState =
    ThroughputGraphState(
        title = title,
        rangeLabels = persistentListOf("1m", "5m", "30m", "1h"),
        selectedRangeIndex = 1,
        downloadSamples =
            persistentListOf(
                // Spec-card SVG path values mapped to [0..1] range; 21 data points
                0.429f,
                0.464f,
                0.393f,
                0.514f,
                0.486f,
                0.607f,
                0.571f,
                0.714f,
                0.679f,
                0.786f,
                0.643f,
                0.700f,
                0.750f,
                0.607f,
                0.657f,
                0.729f,
                0.629f,
                0.679f,
                0.771f,
                0.643f,
                0.700f,
            ),
        uploadSamples =
            persistentListOf(
                // Upload series — lower, steadier band from spec card
                0.179f,
                0.157f,
                0.200f,
                0.171f,
                0.229f,
                0.200f,
                0.250f,
                0.214f,
                0.271f,
                0.236f,
                0.300f,
                0.250f,
                0.286f,
                0.221f,
                0.257f,
                0.300f,
                0.236f,
                0.271f,
                0.321f,
                0.229f,
                0.286f,
            ),
        downNowLabel = downNowLabel,
        upNowLabel = upNowLabel,
        sessionTotalLabel = sessionTotalLabel,
    )
