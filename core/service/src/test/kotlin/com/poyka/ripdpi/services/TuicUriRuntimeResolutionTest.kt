package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import com.poyka.ripdpi.data.mapRelayProfile
import com.poyka.ripdpi.data.uri.ProxyUriCodec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TuicUriRuntimeResolutionTest : UpstreamRelayRuntimeConfigResolverTestFixture() {
    @Test
    fun `imported TUIC fields reach the actual native runtime configuration`() =
        runTest {
            val imported =
                requireNotNull(
                    ProxyUriCodec.parse(
                        "tuic://11111111-2222-3333-4444-555555555555:fixture@203.0.113.7:8443/" +
                            "?sni=tls.example&alpn=tuic%2Ch3&congestion_control=new_reno#Node",
                    ),
                )
            val mapped = requireNotNull(mapRelayProfile(imported))
            val profiles = TestRelayProfileStore().apply { save(mapped.profile) }
            val credentials = TestRelayCredentialStore().apply { save(mapped.credentials) }
            val resolved =
                resolver(profiles, credentials).resolve(
                    RipDpiRelayConfig(enabled = true, kind = RelayKindTuicV5, profileId = imported.id),
                    testRelayResolutionInputs(),
                    expectedSelection = RuntimeConfigurationSelection("native"),
                )

            assertEquals("203.0.113.7", resolved.server)
            assertEquals(8443, resolved.serverPort)
            assertEquals("tls.example", resolved.serverName)
            assertEquals("new_reno", resolved.tuicCongestionControl)
            assertEquals(listOf("tuic", "h3"), resolved.tuicAlpn)
            assertEquals("11111111-2222-3333-4444-555555555555", resolved.tuicUuid)
            assertEquals("fixture", resolved.tuicPassword)
            assertFalse(resolved.tuicZeroRtt)
        }
}
