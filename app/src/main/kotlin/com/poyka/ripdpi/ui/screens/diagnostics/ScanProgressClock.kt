package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsProgressUiModel
import com.poyka.ripdpi.activities.formatScanDurationMs
import kotlinx.coroutines.delay

/** Tick interval for the scan clock; the coarsest label is whole seconds, so one second is enough. */
private const val ScanClockTickMs = 1_000L

/**
 * Pins the scan clock instead of ticking. Previews and tests set this so a screen that renders an
 * active scan stays deterministic and never leaves an endless timer running in the composition.
 */
internal val LocalScanClockMs = staticCompositionLocalOf<Long?> { null }

/**
 * Wall-clock milliseconds, re-read once a second while an active scan is on screen.
 *
 * Progress events arrive only when a step completes, which on a slow step can be many seconds
 * apart; anything derived from the event timestamp appears frozen in the meantime. The ticker is
 * keyed on [scanStartedAtMs] so a new run restarts it and the previous one is cancelled.
 */
@Composable
internal fun rememberScanClockMs(scanStartedAtMs: Long): Long {
    // A pinned clock also means no ticker is started at all, so a preview or a test never leaves an
    // endless timer running in the composition.
    val pinnedMs = LocalScanClockMs.current ?: scanStartedAtMs.takeIf { LocalInspectionMode.current }
    if (pinnedMs != null) {
        return pinnedMs
    }
    val nowMs by produceState(initialValue = System.currentTimeMillis(), key1 = scanStartedAtMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(ScanClockTickMs)
        }
    }
    return nowMs
}

/**
 * Rolling estimate of how fast a scan is completing steps.
 *
 * The scan runs phases whose per-step cost differs by an order of magnitude -- a DNS probe finishes
 * in milliseconds while a connection-concurrency cell takes seconds. A whole-run average therefore
 * under-estimates the remaining time as soon as the run reaches a slow phase, because the fast early
 * steps keep dragging the average down. This keeps an exponentially weighted rate instead, so the
 * estimate follows the phase the scan is actually in.
 *
 * Not thread-safe, and deliberately so: it is owned by a single composition and fed from the same
 * frame that renders it.
 */
internal class ScanRateEstimator(
    private val smoothing: Double = DefaultSmoothing,
) {
    private var lastSampleAtMs: Long = NoSample
    private var lastCompletedSteps: Int = 0
    private var stepsPerMs: Double = 0.0

    /**
     * Feeds one observation. Only a forward move in [completedSteps] updates the rate; repeated
     * samples at the same step count are what a stalled slow step looks like, and letting those
     * decay the rate would make the estimate collapse exactly when the user is waiting longest.
     */
    fun sample(
        nowMs: Long,
        completedSteps: Int,
    ) {
        if (lastSampleAtMs == NoSample) {
            lastSampleAtMs = nowMs
            lastCompletedSteps = completedSteps
            return
        }
        val deltaSteps = completedSteps - lastCompletedSteps
        val deltaMs = nowMs - lastSampleAtMs
        if (deltaSteps <= 0 || deltaMs <= 0L) {
            return
        }
        val observed = deltaSteps.toDouble() / deltaMs.toDouble()
        stepsPerMs =
            if (stepsPerMs <= 0.0) {
                observed
            } else {
                smoothing * observed + (1.0 - smoothing) * stepsPerMs
            }
        lastSampleAtMs = nowMs
        lastCompletedSteps = completedSteps
    }

    /**
     * Remaining milliseconds, or null when there is not enough signal to answer honestly -- before
     * the first forward step, or once the run has no steps left.
     */
    fun remainingMs(
        nowMs: Long,
        completedSteps: Int,
        totalSteps: Int,
    ): Long? {
        val remainingSteps = (totalSteps - completedSteps).coerceAtLeast(0)
        if (stepsPerMs <= 0.0 || totalSteps <= 0 || remainingSteps == 0) {
            return null
        }
        // Time already spent inside the current step counts against the estimate; without this the
        // countdown freezes at the same value for the whole of a slow step.
        val sinceLastStepMs = (nowMs - lastSampleAtMs).coerceAtLeast(0L)
        // Rounded, not truncated: at one step per second a truncating divide loses a millisecond
        // on every estimate, which is visible as a countdown that never quite reaches the mark.
        val projectedMs = Math.round(remainingSteps / stepsPerMs)
        return (projectedMs - sinceLastStepMs).coerceAtLeast(0L)
    }

    private companion object {
        const val NoSample = Long.MIN_VALUE

        /** Weight of the newest observation. Low enough that one anomalous step does not swing the estimate. */
        const val DefaultSmoothing = 0.35
    }
}

/** Elapsed and remaining labels for an active scan, both re-derived on the local clock. */
internal data class ScanTimingLabels(
    val elapsed: String,
    val eta: String?,
)

/**
 * Derives the pair the progress card renders.
 *
 * The engine only republishes progress when a step completes, so any label derived from the last
 * event stands still for the whole of a slow step -- routinely several seconds, which reads as a
 * hung scan. Both labels are therefore re-derived on the local clock instead.
 *
 * Lives here rather than inline in the card so the card stays within its length budget and the
 * clock, the estimator and their formatting sit together.
 */
@Composable
internal fun rememberScanTimingLabels(progress: DiagnosticsProgressUiModel): ScanTimingLabels {
    val scanStartedAtMs = progress.scanStartedAtMs
    val completedSteps = progress.completedSteps
    val totalSteps = progress.totalSteps
    val nowMs = rememberScanClockMs(scanStartedAtMs)
    // Read observably: Locale.getDefault() would not recompose when the user switches locale,
    // and this app ships nine of them.
    val locale = LocalConfiguration.current.locales[0]
    val estimator = remember(scanStartedAtMs) { ScanRateEstimator() }
    estimator.sample(nowMs, completedSteps)
    val eta =
        estimator.remainingMs(nowMs, completedSteps, totalSteps)?.let { remaining ->
            stringResource(R.string.diagnostics_eta_remaining, formatScanDurationMs(remaining, locale))
        }
    return ScanTimingLabels(
        elapsed = formatScanDurationMs((nowMs - scanStartedAtMs).coerceAtLeast(0L), locale),
        eta = eta,
    )
}
