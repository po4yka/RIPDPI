package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.RipDpiProxyCmdPreferences
import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

internal enum class AutolearnResolutionSource(
    val wireValue: String,
) {
    BaselineSettings("baseline_settings"),
    RememberedPolicy("remembered_policy"),
    CommandLine("command_line"),
}

internal enum class AutolearnActivationOutcome(
    val wireValue: String,
) {
    Active("active"),
    Disabled("disabled"),
    Mismatch("mismatch"),
}

internal data class AutolearnActivationReceipt(
    val persistedEnabled: Boolean,
    val resolvedEnabled: Boolean,
    val effectiveEnabled: Boolean,
    val resolutionSource: AutolearnResolutionSource,
    val outcome: AutolearnActivationOutcome,
    val runtimeId: String,
    val mode: Mode,
    val policySignature: String?,
    val fingerprintHash: String?,
    val generation: Long,
    val capturedAt: Long,
)

internal fun createAutolearnActivationReceipt(
    session: ServiceRuntimeSession,
    resolution: ConnectionPolicyResolution,
    evidence: RuntimeStartEvidence.ProxySnapshot,
    generation: Long,
    observedAt: Long,
): AutolearnActivationReceipt {
    val persistedEnabled = resolution.settings.hostAutolearnEnabled
    val resolvedEnabled = resolution.resolvedAutolearnEnabled()
    val effectiveEnabled = evidence.snapshot.autolearnEnabled
    return AutolearnActivationReceipt(
        persistedEnabled = persistedEnabled,
        resolvedEnabled = resolvedEnabled,
        effectiveEnabled = effectiveEnabled,
        resolutionSource = resolution.autolearnResolutionSource(),
        outcome =
            when {
                resolvedEnabled != effectiveEnabled -> AutolearnActivationOutcome.Mismatch
                effectiveEnabled -> AutolearnActivationOutcome.Active
                else -> AutolearnActivationOutcome.Disabled
            },
        runtimeId = session.runtimeId,
        mode = session.mode,
        policySignature = resolution.policySignature,
        fingerprintHash = resolution.fingerprintHash,
        generation = generation,
        capturedAt = evidence.snapshot.capturedAt.takeIf { it > 0L } ?: observedAt,
    )
}

internal fun AutolearnActivationReceipt.toNativeSessionEvent(): NativeSessionEventEntity =
    NativeSessionEventEntity(
        id = stableEventId(),
        source = "app",
        level = if (outcome == AutolearnActivationOutcome.Mismatch) "warn" else "info",
        message =
            "autolearn_activation " +
                "persisted=${persistedEnabled.stateToken()} " +
                "resolved=${resolvedEnabled.stateToken()} " +
                "effective=${effectiveEnabled.stateToken()} " +
                "source=${resolutionSource.wireValue}",
        createdAt = capturedAt,
        runtimeId = runtimeId,
        mode = mode.preferenceValue,
        policySignature = policySignature,
        fingerprintHash = fingerprintHash,
        subsystem = "autolearn_activation",
        attemptSequence = generation,
        stage = "runtime_ready",
        outcome = outcome.wireValue,
    )

internal fun interface AutolearnActivationRecorder {
    suspend fun record(receipt: AutolearnActivationReceipt)
}

@Singleton
internal class RoomAutolearnActivationRecorder
    @Inject
    constructor(
        private val artifactWriteStore: DiagnosticsArtifactWriteStore,
    ) : AutolearnActivationRecorder {
        override suspend fun record(receipt: AutolearnActivationReceipt) {
            artifactWriteStore.insertNativeSessionEvent(receipt.toNativeSessionEvent())
        }
    }

@Singleton
internal class AutolearnActivationReceiptPublisher
    @Inject
    constructor(
        private val recorder: AutolearnActivationRecorder,
    ) {
        suspend fun publish(
            session: ServiceRuntimeSession,
            resolution: ConnectionPolicyResolution,
            evidence: RuntimeStartEvidence,
            observedAt: Long,
        ): AutolearnActivationReceipt? {
            val proxyEvidence = evidence as? RuntimeStartEvidence.ProxySnapshot ?: return null
            val generation = session.nextAutolearnActivationGeneration()
            val receipt = classifyReceipt(session, resolution, proxyEvidence, generation, observedAt)
            return receipt?.let { persistReceipt(it, generation) }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun classifyReceipt(
            session: ServiceRuntimeSession,
            resolution: ConnectionPolicyResolution,
            evidence: RuntimeStartEvidence.ProxySnapshot,
            generation: Long,
            observedAt: Long,
        ): AutolearnActivationReceipt? =
            try {
                createAutolearnActivationReceipt(session, resolution, evidence, generation, observedAt)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                logWarning(kind = "classification_failed", generation = generation, error = error)
                null
            }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun persistReceipt(
            receipt: AutolearnActivationReceipt,
            generation: Long,
        ): AutolearnActivationReceipt? =
            try {
                recorder.record(receipt)
                receipt
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                logWarning(kind = "persistence_failed", generation = generation, error = error)
                null
            }

        private fun logWarning(
            kind: String,
            generation: Long,
            error: Exception,
        ) {
            Logger.w {
                "autolearn_activation_warning kind=$kind " +
                    "generation=$generation failure_class=${error.javaClass.simpleName}"
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AutolearnActivationRecorderModule {
    @Binds
    @Singleton
    abstract fun bindAutolearnActivationRecorder(
        recorder: RoomAutolearnActivationRecorder,
    ): AutolearnActivationRecorder
}

private fun ConnectionPolicyResolution.autolearnResolutionSource(): AutolearnResolutionSource =
    when {
        settings.enableCmdSettings -> AutolearnResolutionSource.CommandLine
        rememberedPolicyAppliedByExactMatch == true -> AutolearnResolutionSource.RememberedPolicy
        else -> AutolearnResolutionSource.BaselineSettings
    }

private fun ConnectionPolicyResolution.resolvedAutolearnEnabled(): Boolean =
    when (val preferences = proxyPreferences) {
        is RipDpiProxyCmdPreferences -> {
            preferences.args.any { it in AutolearnCommandLineFlags }
        }

        else -> {
            requireNotNull(decodeRipDpiProxyUiPreferences(preferences.toNativeConfigJson())) {
                "Resolved proxy preferences do not contain a UI Autolearn section"
            }.hostAutolearn.enabled
        }
    }

private fun AutolearnActivationReceipt.stableEventId(): String {
    val digest =
        MessageDigest
            .getInstance("SHA-256")
            .digest("$runtimeId|$generation".toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    return "autolearn-activation-${digest.take(StableEventIdHexLength)}"
}

private fun Boolean.stateToken(): String = if (this) "enabled" else "disabled"

private val AutolearnCommandLineFlags =
    setOf(
        "--host-autolearn",
        "--host-autolearn-penalty-ttl",
        "--host-autolearn-max-hosts",
        "--host-autolearn-file",
    )

private const val StableEventIdHexLength = 24
