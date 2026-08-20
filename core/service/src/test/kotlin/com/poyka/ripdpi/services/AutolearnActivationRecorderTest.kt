package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiHostAutolearnConfig
import com.poyka.ripdpi.core.RipDpiProxyCmdPreferences
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AutolearnActivationRecorderTest {
    @Test
    @Suppress("LongMethod")
    fun `records privacy safe activation receipt for each resolution source and mismatch outcome`() =
        runTest {
            val cases =
                listOf(
                    Case(
                        name = "baseline active",
                        resolution =
                            sampleResolution(
                                mode = Mode.Proxy,
                                settings =
                                    AppSettingsSerializer.defaultValue
                                        .toBuilder()
                                        .setHostAutolearnEnabled(true)
                                        .build(),
                                proxyPreferences =
                                    RipDpiProxyUIPreferences(
                                        hostAutolearn = RipDpiHostAutolearnConfig(enabled = true),
                                    ),
                                matchedPolicy = null,
                                policySignature = "policy-baseline",
                                networkScopeKey = "fingerprint-baseline",
                            ),
                        effectiveEnabled = true,
                        source = "baseline_settings",
                        outcome = "active",
                    ),
                    Case(
                        name = "remembered disabled",
                        resolution =
                            sampleResolution(
                                mode = Mode.Proxy,
                                settings =
                                    AppSettingsSerializer.defaultValue
                                        .toBuilder()
                                        .setHostAutolearnEnabled(true)
                                        .build(),
                                proxyPreferences =
                                    RipDpiProxyUIPreferences(
                                        hostAutolearn = RipDpiHostAutolearnConfig(enabled = false),
                                    ),
                                matchedPolicy = sampleRememberedPolicyEntity(mode = Mode.Proxy),
                                policySignature = "policy-remembered",
                                networkScopeKey = "fingerprint-remembered",
                            ).copy(rememberedPolicyAppliedByExactMatch = true),
                        effectiveEnabled = false,
                        source = "remembered_policy",
                        outcome = "disabled",
                    ),
                    Case(
                        name = "command-line active",
                        resolution =
                            sampleResolution(
                                mode = Mode.Proxy,
                                settings =
                                    AppSettingsSerializer.defaultValue
                                        .toBuilder()
                                        .setEnableCmdSettings(true)
                                        .setHostAutolearnEnabled(false)
                                        .build(),
                                proxyPreferences =
                                    RipDpiProxyCmdPreferences(
                                        arrayOf(
                                            "ripdpi",
                                            "--host-autolearn",
                                            "--host-autolearn-file=/private/raw/hosts.json",
                                        ),
                                    ),
                                matchedPolicy = null,
                                policySignature = "policy-command-line",
                                networkScopeKey = "fingerprint-command-line",
                            ),
                        effectiveEnabled = true,
                        source = "command_line",
                        outcome = "active",
                    ),
                    Case(
                        name = "request-effective mismatch",
                        resolution =
                            sampleResolution(
                                mode = Mode.Proxy,
                                settings =
                                    AppSettingsSerializer.defaultValue
                                        .toBuilder()
                                        .setHostAutolearnEnabled(true)
                                        .build(),
                                proxyPreferences =
                                    RipDpiProxyUIPreferences(
                                        hostAutolearn =
                                            RipDpiHostAutolearnConfig(
                                                enabled = true,
                                                storePath = "/private/raw/hosts.json",
                                            ),
                                    ),
                                matchedPolicy = null,
                                policySignature = "policy-mismatch",
                                networkScopeKey = "fingerprint-mismatch",
                            ),
                        effectiveEnabled = false,
                        source = "baseline_settings",
                        outcome = "mismatch",
                    ),
                )

            cases.forEachIndexed { index, case ->
                val store = RecordingAutolearnArtifactWriteStore()
                val recorder = RoomAutolearnActivationRecorder(store)
                val receipt =
                    createAutolearnActivationReceipt(
                        session = ProxyRuntimeSession(runtimeId = "runtime-${case.name}"),
                        resolution = case.resolution,
                        evidence =
                            RuntimeStartEvidence.ProxySnapshot(
                                NativeRuntimeSnapshot(
                                    source = "proxy",
                                    state = "running",
                                    health = "healthy",
                                    listenerAddress = "127.0.0.1:${18_080 + index}",
                                    autolearnEnabled = case.effectiveEnabled,
                                    capturedAt = 1_787_231_291_794L + index,
                                ),
                            ),
                        generation = index + 1L,
                        observedAt = 1_787_231_299_999L,
                    )

                recorder.record(receipt)

                val event = requireNotNull(store.event) { "missing event for ${case.name}" }
                assertEquals(case.source, event.message.substringAfter("source="))
                assertEquals(case.outcome, event.outcome)
                assertEquals("autolearn_activation", event.subsystem)
                assertEquals("runtime_ready", event.stage)
                assertEquals(index + 1L, event.attemptSequence)
                assertEquals("runtime-${case.name}", event.runtimeId)
                assertEquals(case.resolution.policySignature, event.policySignature)
                assertEquals(case.resolution.fingerprintHash, event.fingerprintHash)
                assertFalse(event.message.contains("127.0.0.1"))
                assertFalse(event.message.contains("/private/raw"))
                assertFalse(event.message.contains("--host-autolearn-file"))
            }
        }

    private data class Case(
        val name: String,
        val resolution: ConnectionPolicyResolution,
        val effectiveEnabled: Boolean,
        val source: String,
        val outcome: String,
    )
}

private class RecordingAutolearnArtifactWriteStore : DiagnosticsArtifactWriteStore {
    var event: NativeSessionEventEntity? = null

    override suspend fun upsertSnapshot(snapshot: NetworkSnapshotEntity) = Unit

    override suspend fun upsertContextSnapshot(snapshot: DiagnosticContextEntity) = Unit

    override suspend fun insertTelemetrySample(sample: TelemetrySampleEntity) = Unit

    override suspend fun insertNativeSessionEvent(event: NativeSessionEventEntity) {
        this.event = event
    }

    override suspend fun insertExportRecord(record: ExportRecordEntity) = Unit
}
