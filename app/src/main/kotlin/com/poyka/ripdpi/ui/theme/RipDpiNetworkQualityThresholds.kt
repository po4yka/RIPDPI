package com.poyka.ripdpi.ui.theme

/**
 * Presentation thresholds for [com.poyka.ripdpi.data.ConnectionQualitySnapshot]
 * -> tone resolution. These are UI-policy decisions per the design's P5
 * decision-matrix row "Thresholds" — NOT data-plane policy.
 *
 * Justifications:
 *   loss 2 % warning / 8 % critical — RFC 8083 interactive-media tiers
 *   RTT 300 ms warning / 800 ms critical — Telegram VoIP MOS curves
 *   jitter 30 ms warning / 100 ms critical — matches the strip-design
 *     intent (warning at "noticeable", critical at "actively bad")
 *   minSampleCountForVerdict = 12 — suppresses false alarms during
 *     cold start (1 Hz x 12 s = stable mean before alarming)
 *
 * Hysteresis: once Warning is shown, it stays until the value drops
 * to 0.7 × threshold. Implemented by callers that track previous tone;
 * the resolver itself is stateless.
 *
 * Design ref: docs/architecture/G008_SUBSYSTEMS_DESIGN.md P5
 * decision-matrix row "Thresholds".
 */
data class RipDpiNetworkQualityThresholds(
    val lossWarnPct: Float = 2.0f,
    val lossCriticalPct: Float = 8.0f,
    val rttWarnMs: Long = 300L,
    val rttCriticalMs: Long = 800L,
    val jitterWarnMs: Long = 30L,
    val jitterCriticalMs: Long = 100L,
    val minSampleCountForVerdict: Long = 12L,
    val hysteresisFactor: Float = 0.7f,
)

val DefaultRipDpiNetworkQualityThresholds: RipDpiNetworkQualityThresholds =
    RipDpiNetworkQualityThresholds()
