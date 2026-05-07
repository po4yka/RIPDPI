package com.poyka.ripdpi.activities

import com.poyka.ripdpi.BuildConfig
import javax.inject.Inject

private const val NanosPerMillisecond = 1_000_000L

internal class DiagnosticsPerformanceUiStateFactory
    @Inject
    constructor() {
        private var buildSequence = 0L

        fun startTimer(): DiagnosticsUiBuildTimer = DiagnosticsUiBuildTimer(System.nanoTime())

        fun build(
            input: DiagnosticsUiStateInput,
            timer: DiagnosticsUiBuildTimer,
        ): DiagnosticsPerformanceUiModel? =
            if (BuildConfig.DEBUG) {
                DiagnosticsPerformanceUiModel(
                    buildSequence = ++buildSequence,
                    totalDurationMillis = nanosToMillis(System.nanoTime() - timer.startedAtNs),
                    eventMappingDurationMillis = nanosToMillis(timer.eventMappingDurationNs),
                    resolveDurationMillis = nanosToMillis(timer.resolveDurationNs),
                    overviewDurationMillis = nanosToMillis(timer.overviewDurationNs),
                    scanDurationMillis = nanosToMillis(timer.scanDurationNs),
                    liveDurationMillis = nanosToMillis(timer.liveDurationNs),
                    sessionsDurationMillis = nanosToMillis(timer.sessionsDurationNs),
                    approachesDurationMillis = nanosToMillis(timer.approachesDurationNs),
                    eventsDurationMillis = nanosToMillis(timer.eventsDurationNs),
                    shareDurationMillis = nanosToMillis(timer.shareDurationNs),
                    telemetryCount = input.telemetry.size,
                    nativeEventCount = input.nativeEvents.size,
                    sessionCount = input.sessions.size,
                )
            } else {
                null
            }

        private fun nanosToMillis(durationNs: Long): Double = durationNs / NanosPerMillisecond.toDouble()
    }

internal class DiagnosticsUiBuildTimer(
    val startedAtNs: Long,
) {
    var eventMappingDurationNs: Long = 0L
        private set
    var resolveDurationNs: Long = 0L
        private set
    var overviewDurationNs: Long = 0L
        private set
    var scanDurationNs: Long = 0L
        private set
    var liveDurationNs: Long = 0L
        private set
    var sessionsDurationNs: Long = 0L
        private set
    var approachesDurationNs: Long = 0L
        private set
    var eventsDurationNs: Long = 0L
        private set
    var shareDurationNs: Long = 0L
        private set

    inline fun <T> measureEventMapping(block: () -> T): T = measureDuration({ eventMappingDurationNs = it }, block)

    inline fun <T> measureResolve(block: () -> T): T = measureDuration({ resolveDurationNs = it }, block)

    inline fun <T> measureOverview(block: () -> T): T = measureDuration({ overviewDurationNs = it }, block)

    inline fun <T> measureScan(block: () -> T): T = measureDuration({ scanDurationNs = it }, block)

    inline fun <T> measureLive(block: () -> T): T = measureDuration({ liveDurationNs = it }, block)

    inline fun <T> measureSessions(block: () -> T): T = measureDuration({ sessionsDurationNs = it }, block)

    inline fun <T> measureApproaches(block: () -> T): T = measureDuration({ approachesDurationNs = it }, block)

    inline fun <T> measureEvents(block: () -> T): T = measureDuration({ eventsDurationNs = it }, block)

    inline fun <T> measureShare(block: () -> T): T = measureDuration({ shareDurationNs = it }, block)

    inline fun <T> measureDuration(
        record: (Long) -> Unit,
        block: () -> T,
    ): T {
        val startedAt = System.nanoTime()
        return block().also { record(System.nanoTime() - startedAt) }
    }
}
