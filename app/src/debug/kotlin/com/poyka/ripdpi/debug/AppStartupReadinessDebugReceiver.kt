package com.poyka.ripdpi.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import com.poyka.ripdpi.AppStartupInitializer
import com.poyka.ripdpi.AppStartupReadinessState
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactQueryStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsDurableStateStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.thread

/** Debug-only process boundary used to verify production startup recovery. */
class AppStartupReadinessDebugReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ActionAwaitReady) return

        val pendingResult = goAsync()
        thread(name = "app-startup-readiness-debug", isDaemon = true) {
            val extras = Bundle().apply { putInt(ExtraProcessPid, Process.myPid()) }
            val resultCode =
                runCatching {
                    val readiness =
                        EntryPointAccessors
                            .fromApplication(
                                context.applicationContext,
                                AppStartupReadinessDebugEntryPoint::class.java,
                            ).appStartupInitializer()
                    val terminalState =
                        runBlocking(Dispatchers.Default) {
                            withTimeout(StartupTimeoutMs) {
                                readiness.readiness.first { state ->
                                    state != AppStartupReadinessState.Pending
                                }
                            }
                        }
                    check(terminalState == AppStartupReadinessState.Ready) {
                        "App startup recovery ended in $terminalState"
                    }
                    intent.getStringExtra(ExtraExpectedRunGeneration)?.let { runGeneration ->
                        val pendingGenerationKey =
                            requireNotNull(intent.getStringExtra(ExtraPendingGenerationKey)) {
                                "Missing $ExtraPendingGenerationKey extra"
                            }
                        val entryPoint =
                            EntryPointAccessors.fromApplication(
                                context.applicationContext,
                                AppStartupReadinessDebugEntryPoint::class.java,
                            )
                        val recoveredEvent =
                            runBlocking(Dispatchers.IO) {
                                check(
                                    entryPoint
                                        .diagnosticsDurableStateStore()
                                        .getDurableState(pendingGenerationKey) == null,
                                ) {
                                    "Remote acceptance generation remained pending after startup"
                                }
                                entryPoint
                                    .diagnosticsArtifactQueryStore()
                                    .getGlobalNativeEvents(limit = 100)
                                    .single { event ->
                                        event.runtimeId == runGeneration &&
                                            event.message.contains("phase=run_interrupted")
                                    }
                            }
                        extras.putString(ExtraRecoveredRuntimeId, recoveredEvent.runtimeId)
                        extras.putString(ExtraRecoveredSource, recoveredEvent.source)
                        extras.putString(ExtraRecoveredSubsystem, recoveredEvent.subsystem)
                        extras.putString(ExtraRecoveredMessage, recoveredEvent.message)
                        extras.putString(ExtraRecoveredSessionId, recoveredEvent.sessionId)
                        extras.putString(ExtraRecoveredConnectionSessionId, recoveredEvent.connectionSessionId)
                        extras.putString(ExtraRecoveredFingerprintHash, recoveredEvent.fingerprintHash)
                        extras.putString(ExtraRecoveredPolicySignature, recoveredEvent.policySignature)
                    }
                    extras.putBoolean(ExtraOk, true)
                    Activity.RESULT_OK
                }.getOrElse { error ->
                    extras.putBoolean(ExtraOk, false)
                    extras.putString(ExtraErrorClass, error::class.java.name)
                    extras.putString(ExtraErrorMessage, error.message)
                    Activity.RESULT_CANCELED
                }

            pendingResult.resultCode = resultCode
            pendingResult.setResultExtras(extras)
            pendingResult.finish()
        }
    }

    companion object {
        const val ActionAwaitReady = "com.poyka.ripdpi.debug.AWAIT_APP_STARTUP_READY"
        const val ExtraOk = "ok"
        const val ExtraProcessPid = "process_pid"
        const val ExtraErrorClass = "error_class"
        const val ExtraErrorMessage = "error_message"
        const val ExtraExpectedRunGeneration = "expected_run_generation"
        const val ExtraPendingGenerationKey = "pending_generation_key"
        const val ExtraRecoveredRuntimeId = "recovered_runtime_id"
        const val ExtraRecoveredSource = "recovered_source"
        const val ExtraRecoveredSubsystem = "recovered_subsystem"
        const val ExtraRecoveredMessage = "recovered_message"
        const val ExtraRecoveredSessionId = "recovered_session_id"
        const val ExtraRecoveredConnectionSessionId = "recovered_connection_session_id"
        const val ExtraRecoveredFingerprintHash = "recovered_fingerprint_hash"
        const val ExtraRecoveredPolicySignature = "recovered_policy_signature"

        private const val StartupTimeoutMs = 10_000L
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface AppStartupReadinessDebugEntryPoint {
    fun appStartupInitializer(): AppStartupInitializer

    fun diagnosticsDurableStateStore(): DiagnosticsDurableStateStore

    fun diagnosticsArtifactQueryStore(): DiagnosticsArtifactQueryStore
}
