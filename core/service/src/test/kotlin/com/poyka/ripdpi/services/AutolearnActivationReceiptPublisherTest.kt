package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiHostAutolearnConfig
import com.poyka.ripdpi.core.RipDpiProxyCmdPreferences
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutolearnActivationReceiptPublisherTest {
    @Test
    fun baselineActivationRecordsAllLayersAndPrivacySafeEnvelope() =
        runTest {
            val recorder = RecordingAutolearnActivationRecorder()
            val publisher = AutolearnActivationReceiptPublisher(recorder)
            val resolution = enabledUiResolution()

            val receipt =
                publisher.publish(
                    session = ProxyRuntimeSession(runtimeId = "runtime-sensitive"),
                    resolution = resolution,
                    evidence = readyEvidence(enabled = true, capturedAt = 41L),
                    observedAt = 42L,
                )

            requireNotNull(receipt)
            assertTrue(receipt.persistedEnabled)
            assertTrue(receipt.resolvedEnabled)
            assertTrue(receipt.effectiveEnabled)
            assertEquals(AutolearnResolutionSource.BaselineSettings, receipt.resolutionSource)
            assertEquals(AutolearnActivationOutcome.Active, receipt.outcome)
            assertEquals(41L, receipt.capturedAt)

            val event = recorder.receipts.single().toNativeSessionEvent()
            assertEquals("autolearn_activation", event.subsystem)
            assertEquals("runtime_ready", event.stage)
            assertEquals("active", event.outcome)
            assertEquals(1L, event.attemptSequence)
            assertEquals(
                "autolearn_activation persisted=enabled resolved=enabled effective=enabled source=baseline_settings",
                event.message,
            )
            assertFalse(event.message.contains("runtime-sensitive"))
            assertFalse(event.message.contains("policy-sensitive"))
            assertFalse(event.message.contains("fingerprint-sensitive"))
        }

    @Test
    fun rememberedAndCommandLineSourcesAreClassifiedWithoutSerializingArguments() =
        runTest {
            val rememberedRecorder = RecordingAutolearnActivationRecorder()
            val rememberedResolution =
                enabledUiResolution().copy(
                    matchedNetworkPolicy = sampleRememberedPolicyEntity(),
                    rememberedPolicyAppliedByExactMatch = true,
                )
            AutolearnActivationReceiptPublisher(rememberedRecorder).publish(
                session = ProxyRuntimeSession("remembered-runtime"),
                resolution = rememberedResolution,
                evidence = readyEvidence(enabled = true),
                observedAt = 10L,
            )
            assertEquals(
                AutolearnResolutionSource.RememberedPolicy,
                rememberedRecorder.receipts.single().resolutionSource,
            )

            val commandLineRecorder = RecordingAutolearnActivationRecorder()
            val commandLine =
                enabledUiResolution().copy(
                    settings =
                        enabledSettings()
                            .toBuilder()
                            .setEnableCmdSettings(true)
                            .build(),
                    proxyPreferences =
                        RipDpiProxyCmdPreferences(
                            arrayOf("ripdpi", "--host-autolearn-file", "/private/store/path"),
                        ),
                )
            AutolearnActivationReceiptPublisher(commandLineRecorder).publish(
                session = ProxyRuntimeSession("command-runtime"),
                resolution = commandLine,
                evidence = readyEvidence(enabled = true),
                observedAt = 11L,
            )
            val commandReceipt = commandLineRecorder.receipts.single()
            assertEquals(AutolearnResolutionSource.CommandLine, commandReceipt.resolutionSource)
            assertTrue(commandReceipt.resolvedEnabled)
            assertFalse(commandReceipt.toNativeSessionEvent().message.contains("/private/store/path"))
        }

    @Test
    fun nativeDivergenceIsAnExplicitMismatch() =
        runTest {
            val recorder = RecordingAutolearnActivationRecorder()

            AutolearnActivationReceiptPublisher(recorder).publish(
                session = VpnRuntimeSession("vpn-runtime"),
                resolution = enabledUiResolution(mode = Mode.VPN),
                evidence = readyEvidence(enabled = false),
                observedAt = 12L,
            )

            val receipt = recorder.receipts.single()
            assertTrue(receipt.resolvedEnabled)
            assertFalse(receipt.effectiveEnabled)
            assertEquals(AutolearnActivationOutcome.Mismatch, receipt.outcome)
            assertEquals("warn", receipt.toNativeSessionEvent().level)
            assertEquals("mismatch", receipt.toNativeSessionEvent().outcome)
        }

    @Test
    fun eachReplacementGetsADistinctGenerationAndStableEventId() =
        runTest {
            val recorder = RecordingAutolearnActivationRecorder()
            val publisher = AutolearnActivationReceiptPublisher(recorder)
            val session = ProxyRuntimeSession("runtime-one")
            val resolution = enabledUiResolution()

            publisher.publish(session, resolution, readyEvidence(enabled = true), observedAt = 20L)
            publisher.publish(session, resolution, readyEvidence(enabled = true), observedAt = 21L)

            assertEquals(listOf(1L, 2L), recorder.receipts.map { it.generation })
            assertNotEquals(
                recorder.receipts[0].toNativeSessionEvent().id,
                recorder.receipts[1].toNativeSessionEvent().id,
            )
        }

    @Test
    fun providerOwnedEvidenceDoesNotRecordOrConsumeAGeneration() =
        runTest {
            val recorder = RecordingAutolearnActivationRecorder()
            val publisher = AutolearnActivationReceiptPublisher(recorder)
            val session = VpnRuntimeSession("provider-runtime")
            val resolution = enabledUiResolution(mode = Mode.VPN)

            assertNull(
                publisher.publish(
                    session,
                    resolution,
                    RuntimeStartEvidence.NotApplicable,
                    observedAt = 30L,
                ),
            )
            publisher.publish(session, resolution, readyEvidence(enabled = true), observedAt = 31L)

            assertEquals(1L, recorder.receipts.single().generation)
        }

    @Test
    fun storageFailureDoesNotFailRuntimePublication() =
        runTest {
            val publisher =
                AutolearnActivationReceiptPublisher(
                    AutolearnActivationRecorder { error("database unavailable") },
                )

            val result =
                publisher.publish(
                    ProxyRuntimeSession("runtime"),
                    enabledUiResolution(),
                    readyEvidence(enabled = true),
                    observedAt = 40L,
                )

            assertNull(result)
        }

    @Test(expected = CancellationException::class)
    fun cancellationFromStoragePropagates() =
        runTest {
            val publisher =
                AutolearnActivationReceiptPublisher(
                    AutolearnActivationRecorder { throw CancellationException("cancel") },
                )

            publisher.publish(
                ProxyRuntimeSession("runtime"),
                enabledUiResolution(),
                readyEvidence(enabled = true),
                observedAt = 50L,
            )
        }

    private fun enabledUiResolution(mode: Mode = Mode.Proxy): ConnectionPolicyResolution =
        sampleResolution(
            mode = mode,
            settings = enabledSettings(),
            proxyPreferences =
                RipDpiProxyUIPreferences(
                    hostAutolearn = RipDpiHostAutolearnConfig(enabled = true),
                ),
            policySignature = "policy-sensitive",
            networkScopeKey = "fingerprint-sensitive",
        )

    private fun enabledSettings() =
        AppSettingsSerializer.defaultValue
            .toBuilder()
            .setHostAutolearnEnabled(true)
            .build()

    private fun readyEvidence(
        enabled: Boolean,
        capturedAt: Long = 0L,
    ): RuntimeStartEvidence.ProxySnapshot =
        RuntimeStartEvidence.ProxySnapshot(
            NativeRuntimeSnapshot(
                source = "proxy",
                state = "running",
                autolearnEnabled = enabled,
                capturedAt = capturedAt,
            ),
        )
}

private class RecordingAutolearnActivationRecorder : AutolearnActivationRecorder {
    val receipts = mutableListOf<AutolearnActivationReceipt>()

    override suspend fun record(receipt: AutolearnActivationReceipt) {
        receipts += receipt
    }
}
