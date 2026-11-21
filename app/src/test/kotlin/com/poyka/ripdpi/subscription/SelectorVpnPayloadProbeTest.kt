package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.AppliedRuntimeConfigurationSource
import com.poyka.ripdpi.data.DiagnosticsInPathRouteLease
import com.poyka.ripdpi.data.DiagnosticsProxyCredentials
import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.InPathRouteLeaseAcquisition
import com.poyka.ripdpi.data.InPathRouteUnavailableReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProfileQualityReason
import com.poyka.ripdpi.data.RawPathExecutionResult
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.RuntimeConfigurationApplyReason
import com.poyka.ripdpi.data.RuntimeConfigurationDns
import com.poyka.ripdpi.data.RuntimeConfigurationPendingStatus
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import com.poyka.ripdpi.data.RuntimeConfigurationStrategy
import com.poyka.ripdpi.data.fakes.FakeAppSettingsRepository
import com.poyka.ripdpi.services.CandidateHttpPayloadProbe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class SelectorVpnPayloadProbeTest {
    private val configuration = configuration(revision = 1L)
    private val applications =
        MutableStateFlow<Map<Mode, RuntimeConfigurationApplication>>(
            mapOf(Mode.VPN to RuntimeConfigurationApplication.Applied(configuration)),
        )
    private val coordinator = FakeRouteCoordinator(lease(closedLoopbackPort()))
    private val settings = FakeAppSettingsRepository()
    private val probe =
        SelectorVpnPayloadProbe(
            object : AppliedRuntimeConfigurationSource {
                override val applications = this@SelectorVpnPayloadProbeTest.applications
                override val pendingChanges = MutableStateFlow(emptyMap<Mode, RuntimeConfigurationPendingStatus>())
            },
            coordinator,
            settings,
            CandidateHttpPayloadProbe(),
        )

    @Test
    fun `noDomain leaves the active member to isolated candidate probes`() =
        runTest {
            settings.update { setNoDomain(true) }

            assertNull(probe.capture("g"))
            assertEquals(0, coordinator.acquisitions)
        }

    @Test
    fun `a VPN that carries another group has no path for this one`() =
        runTest {
            assertNull(probe.capture("other"))
            assertEquals(0, coordinator.acquisitions)
        }

    @Test
    fun `the applied route stays current until its configuration or lease changes`() =
        runTest {
            val path = requireNotNull(probe.capture("g"))
            assertNotNull(path.lease)
            assertTrue(probe.isCurrent(path))

            coordinator.revoked = true
            assertFalse(probe.isCurrent(path))
            coordinator.revoked = false
            assertTrue(probe.isCurrent(path))

            applications.value =
                mapOf(Mode.VPN to RuntimeConfigurationApplication.Applied(configuration(revision = 2L)))
            assertFalse(probe.isCurrent(path))
        }

    @Test
    fun `an unavailable lease gives a path without route authority`() =
        runTest {
            coordinator.available = false

            val path = requireNotNull(probe.capture("g"))
            assertNull(path.lease)
            assertFalse(probe.isCurrent(path))
            assertNull(probe.measure(path, policy()))
        }

    @Test
    fun `a route revoked during the transfer yields no result`() =
        runTest {
            val path = requireNotNull(probe.capture("g"))
            assertEquals(
                ProfileQualityReason.TransferFailed,
                probe.measure(path, policy())?.reason,
            )

            coordinator.revokeAfterValidations = coordinator.validations + 1
            assertNull(probe.measure(path, policy()))
        }

    private fun configuration(revision: Long): AppliedRuntimeConfiguration {
        val selection = RuntimeConfigurationSelection("relay", selectorGroupId = "g", selectorMemberId = "active")
        return AppliedRuntimeConfiguration(
            runtimeId = "runtime",
            revision = revision,
            appliedAt = 0L,
            mode = Mode.VPN,
            requestedSelection = selection,
            effectiveSelection = selection,
            dns = RuntimeConfigurationDns("plain_udp", "system"),
            strategy = RuntimeConfigurationStrategy(enabled = false),
            reason = RuntimeConfigurationApplyReason.InitialStart,
        )
    }

    private fun lease(port: Int) =
        DiagnosticsInPathRouteLease(
            "runtime",
            1L,
            1L,
            "127.0.0.1",
            port,
            DiagnosticsProxyCredentials("user", "password"),
        )

    // A port with no listener: the transfer fails at once, so the test controls only route authority.
    private fun closedLoopbackPort(): Int = ServerSocket(0).use { it.localPort }

    private class FakeRouteCoordinator(
        private val lease: DiagnosticsInPathRouteLease,
    ) : DiagnosticsRuntimeCoordinator {
        var available = true
        var revoked = false
        var revokeAfterValidations = Int.MAX_VALUE
        var acquisitions = 0
        var validations = 0

        override suspend fun runRawPathScan(block: suspend () -> Unit): RawPathExecutionResult = error("unused")

        override suspend fun runAutomaticRawPathScan(block: suspend () -> Unit): RawPathExecutionResult =
            error("unused")

        override suspend fun acquireInPathRouteLease(): InPathRouteLeaseAcquisition {
            acquisitions++
            return if (available) {
                InPathRouteLeaseAcquisition.Acquired(lease)
            } else {
                InPathRouteLeaseAcquisition.Unavailable(InPathRouteUnavailableReason.LeaseUnpublished)
            }
        }

        override fun validateInPathRouteLease(
            lease: DiagnosticsInPathRouteLease,
        ): InPathRouteLeaseAcquisition.Unavailable? {
            validations++
            return if (revoked || validations > revokeAfterValidations) {
                InPathRouteLeaseAcquisition.Unavailable(InPathRouteUnavailableReason.LeaseRevoked)
            } else {
                null
            }
        }
    }
}
