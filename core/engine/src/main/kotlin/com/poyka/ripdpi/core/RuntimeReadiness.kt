package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * Shared readiness-polling loop for the native runtime wrappers ([RipDpiProxy]
 * and [RipDpiRelay]).
 *
 * Each wrapper arms a [CompletableDeferred] startup signal when it creates its
 * native session, then waits for the runtime to announce readiness. The waiting
 * mechanics — poll telemetry on a fixed interval, watch for the `runtime_ready`
 * native event, honour a timeout, and report the last observed state/event when
 * that timeout fires — were duplicated verbatim across the wrappers; this is the
 * single copy.
 *
 * The wait ends as soon as any of these holds:
 * - [startupSignal] is already (or becomes) completed — its result, including an
 *   exceptional completion, is propagated to the caller through `await()`;
 * - a polled telemetry snapshot carries a `runtime_ready` native event, which
 *   completes [startupSignal] and returns.
 *
 * If neither happens within [timeoutMillis] the loop throws
 * `IllegalStateException` whose message is
 * `"<timeoutMessagePrefix> state=<lastState>[ lastEvent=<lastEvent>]"`, after
 * invoking [onTimeout] with that same string so the caller can log it. The
 * `runtime_ready` check, the polling interval and the timeout semantics are
 * identical to the per-wrapper loops this replaced.
 *
 * @param startupSignal readiness signal armed by the wrapper's start path.
 * @param timeoutMillis upper bound on the wait before failing.
 * @param pollIntervalMillis delay between telemetry polls.
 * @param timeoutMessagePrefix wrapper-specific prefix for the timeout message.
 * @param pollTelemetry suspending telemetry probe, supplied by the wrapper.
 * @param onPoll invoked after every telemetry poll with the snapshot and the
 *   running poll count, for wrapper-specific progress logging.
 * @param onTimeout invoked with the assembled timeout detail string just before
 *   it is thrown, for wrapper-specific timeout logging.
 */
internal suspend fun awaitRuntimeReady(
    startupSignal: CompletableDeferred<Unit>,
    timeoutMillis: Long,
    pollIntervalMillis: Long,
    timeoutMessagePrefix: String,
    pollTelemetry: suspend () -> NativeRuntimeSnapshot,
    onPoll: (telemetry: NativeRuntimeSnapshot, pollCount: Long) -> Unit = { _, _ -> },
    onTimeout: (details: String) -> Unit = {},
) {
    var pollCount = 0L
    var lastState = "idle"
    var lastEventMessage: String? = null
    try {
        withTimeout(timeoutMillis) {
            while (true) {
                if (startupSignal.isCompleted) {
                    startupSignal.await()
                    return@withTimeout
                }

                val telemetry = pollTelemetry()
                lastState = telemetry.state
                lastEventMessage = telemetry.nativeEvents.lastOrNull()?.message
                if (telemetry.hasRuntimeReadyEvent()) {
                    startupSignal.complete(Unit)
                    startupSignal.await()
                    return@withTimeout
                }

                pollCount++
                onPoll(telemetry, pollCount)
                delay(pollIntervalMillis)
            }
        }
    } catch (_: TimeoutCancellationException) {
        val details =
            buildString {
                append(timeoutMessagePrefix)
                append(" state=")
                append(lastState)
                lastEventMessage?.let {
                    append(" lastEvent=")
                    append(it)
                }
            }
        onTimeout(details)
        error(details)
    }
}

private fun NativeRuntimeSnapshot.hasRuntimeReadyEvent(): Boolean = nativeEvents.any { it.kind == "runtime_ready" }
