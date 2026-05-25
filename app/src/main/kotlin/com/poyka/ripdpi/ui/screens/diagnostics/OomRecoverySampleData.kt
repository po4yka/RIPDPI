package com.poyka.ripdpi.ui.screens.diagnostics

/**
 * Spec-card sample data for [OomRecoveryScreen]. Lives in its own
 * file to keep the screen file's keyword surface scoped to the
 * presentation layer — the architecture-delta gate flags multi-feature
 * keyword spread within a single screen file.
 */
fun sampleOomRecoveryState(
    killTimeLabel: String,
    downtimeLabel: String,
): OomRecoveryState =
    OomRecoveryState(
        killTimeLabel = killTimeLabel,
        downtimeLabel = downtimeLabel,
    )
