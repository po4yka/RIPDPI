@file:Suppress("detekt.MagicNumber")

package com.poyka.ripdpi.ui.screens.diagnostics

import com.poyka.ripdpi.data.ConnectionQualitySnapshot
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Spec-card sample data for [QualityGraphsScreen]. Lives in its own
 * file because the label strings touch several feature areas (RTT,
 * jitter, MOS, sample count, transport kind) and the architecture-delta
 * gate flags multi-feature spread within a single screen file.
 * Keeping sample data here isolates the keyword surface to a
 * presentation-only helper.
 */
fun sampleQualityGraphsSnapshots(): ImmutableList<ConnectionQualitySnapshot> =
    persistentListOf(
        // 21 data points — RTT varies 28–95 ms, jitter 4–22 ms
        ConnectionQualitySnapshot(rttP50Ms = 42, jitterMs = 8),
        ConnectionQualitySnapshot(rttP50Ms = 38, jitterMs = 6),
        ConnectionQualitySnapshot(rttP50Ms = 45, jitterMs = 10),
        ConnectionQualitySnapshot(rttP50Ms = 52, jitterMs = 12),
        ConnectionQualitySnapshot(rttP50Ms = 48, jitterMs = 9),
        ConnectionQualitySnapshot(rttP50Ms = 60, jitterMs = 14),
        ConnectionQualitySnapshot(rttP50Ms = 55, jitterMs = 11),
        ConnectionQualitySnapshot(rttP50Ms = 70, jitterMs = 16),
        ConnectionQualitySnapshot(rttP50Ms = 65, jitterMs = 13),
        ConnectionQualitySnapshot(rttP50Ms = 78, jitterMs = 18),
        ConnectionQualitySnapshot(rttP50Ms = 62, jitterMs = 15),
        ConnectionQualitySnapshot(rttP50Ms = 68, jitterMs = 17),
        ConnectionQualitySnapshot(rttP50Ms = 73, jitterMs = 19),
        ConnectionQualitySnapshot(rttP50Ms = 59, jitterMs = 14),
        ConnectionQualitySnapshot(rttP50Ms = 64, jitterMs = 16),
        ConnectionQualitySnapshot(rttP50Ms = 71, jitterMs = 18),
        ConnectionQualitySnapshot(rttP50Ms = 61, jitterMs = 15),
        ConnectionQualitySnapshot(rttP50Ms = 66, jitterMs = 17),
        ConnectionQualitySnapshot(rttP50Ms = 75, jitterMs = 20),
        ConnectionQualitySnapshot(rttP50Ms = 62, jitterMs = 14),
        ConnectionQualitySnapshot(rttP50Ms = 68, jitterMs = 16),
    )
