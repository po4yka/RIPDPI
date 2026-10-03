package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindShadowsocks
import com.poyka.ripdpi.data.RelayProfileRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateRelayProbeConfigurationTest : UpstreamRelayRuntimeConfigResolverTestFixture() {
    @Test
    fun `metadata only experiment updates preserve candidate environment`() =
        runTest {
            var selection = RuntimeExperimentSelection(featureFlags = mapOf("candidate-feature" to true))
            val configuration = configuration(experiments = { selection })
            val before = configuration.capture()
            selection =
                selection.copy(
                    strategyPackId = "new-pack",
                    strategyPackVersion = "new-version",
                    tlsProfileId = "metadata-profile",
                    tlsProfileCatalogVersion = "new-catalog",
                    morphPolicyId = "new-policy",
                )
            assertEquals(before, configuration.capture())
        }

    @Test
    fun `invalid unrelated Worker settings do not block candidate resolution`() =
        runTest {
            val settings =
                TestAppSettingsRepository(
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setEnableCmdSettings(false)
                        .setWsTunnelMode("off")
                        .setWsTunnelWorkerUrl("https://worker.example")
                        .setQuicBindLowPort(true)
                        .setQuicMigrateAfterHandshake(true)
                        .build(),
                )
            val snapshot = settings.snapshot()
            assertThrows(IllegalArgumentException::class.java) { RipDpiProxyUIPreferences.fromSettings(snapshot) }
            val configuration = configuration(settings)
            val environment = configuration.capture()
            val candidate =
                RelayProfileRecord(
                    id = "candidate",
                    kind = RelayKindShadowsocks,
                    server = "candidate.example",
                    serverPort = 8443,
                )
            val resolved =
                configuration.prepare(
                    candidate,
                    RelayCredentialRecord(
                        profileId = candidate.id,
                        shadowsocksMethod = "aes-256-gcm",
                        shadowsocksPassword = credentialFixture(candidate.id),
                    ),
                    environment,
                )
            assertEquals(candidate.server, resolved.server)
            assertEquals(credentialFixture(candidate.id), resolved.shadowsocksPassword)
            assertTrue(resolved.quicBindLowPort)
            assertTrue(resolved.quicMigrateAfterHandshake)
        }

    @Test
    fun `captured feature flags are a snapshot and actual changes invalidate environment`() =
        runTest {
            val flags = mutableMapOf("candidate-feature" to true)
            val configuration = configuration(experiments = { RuntimeExperimentSelection(featureFlags = flags) })
            val before = configuration.capture()
            flags["candidate-feature"] = false
            assertEquals(mapOf("candidate-feature" to true), before.featureFlags)
            assertThrows(UnsupportedOperationException::class.java) {
                (before.featureFlags as MutableMap)["candidate-feature"] = false
            }
            assertNotEquals(before, configuration.capture())
        }

    private fun configuration(
        settings: TestAppSettingsRepository = TestAppSettingsRepository(),
        experiments: () -> RuntimeExperimentSelection = { RuntimeExperimentSelection() },
    ) = CandidateRelayProbeConfiguration(
        resolver = resolver(),
        stateStore = TestServiceStateStore(),
        settings = settings,
        experiments =
            object : RuntimeExperimentSelectionProvider {
                override fun current(): RuntimeExperimentSelection = experiments()
            },
    )
}
