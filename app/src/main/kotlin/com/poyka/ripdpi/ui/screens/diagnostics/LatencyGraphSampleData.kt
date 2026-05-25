package com.poyka.ripdpi.ui.screens.diagnostics

import kotlinx.collections.immutable.persistentListOf

/**
 * Spec-card sample data for [LatencyGraphScreen]. Lives in its own
 * file because the label strings touch several feature areas (RTT,
 * packet loss, spike detection, threshold band) and the
 * architecture-delta gate flags multi-feature spread within a single
 * screen file. Keeping sample data here isolates the keyword surface
 * to a presentation-only helper.
 */
fun sampleLatencyGraphState(
    title: String,
    p50Label: String,
    p95Label: String,
    nowLabel: String,
    p99Label: String,
    spikeCountLabel: String,
    packetLossLabel: String,
): LatencyGraphState =
    LatencyGraphState(
        title = title,
        rttSamples =
            persistentListOf(
                // Spec-card SVG path y-values (0..110 range) mapped to [0..1].
                // Path: M0,70 L20,68 L40,72 L60,65 L80,70 L100,68 L120,58 L140,55
                //       L160,62 L180,48 L200,40 L220,30 L240,25 L260,32 L280,40
                //       L300,52 L320,60 L340,55 L360,58 L380,52 L400,55 L420,42
                //       L440,38 L460,30 L480,22 L500,28 L520,38 L540,48 L560,55
                //       L580,60 L600,52
                // Normalised as (110 - y) / 110 so low y (high on canvas) → high value.
                0.364f, // y=70
                0.382f, // y=68
                0.345f, // y=72
                0.409f, // y=65
                0.364f, // y=70
                0.382f, // y=68
                0.473f, // y=58
                0.500f, // y=55
                0.436f, // y=62
                0.564f, // y=48
                0.636f, // y=40
                0.727f, // y=30
                0.773f, // y=25
                0.709f, // y=32
                0.636f, // y=40
                0.527f, // y=52
                0.455f, // y=60
                0.500f, // y=55
                0.473f, // y=58
                0.527f, // y=52
                0.500f, // y=55
                0.618f, // y=42
                0.655f, // y=38
                0.727f, // y=30
                0.800f, // y=22
                0.745f, // y=28
                0.655f, // y=38
                0.564f, // y=48
                0.500f, // y=55
                0.455f, // y=60
                0.527f, // y=52
            ),
        // Threshold at 100 ms; spec chart height = 110 px, threshold at y=38px ≈ 65% up.
        thresholdFraction = 0.655f,
        p50Label = p50Label,
        p95Label = p95Label,
        nowLabel = nowLabel,
        p99Label = p99Label,
        spikeCountLabel = spikeCountLabel,
        packetLossLabel = packetLossLabel,
        lossFraction = 0.041f,
    )
