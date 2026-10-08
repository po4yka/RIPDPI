package com.poyka.ripdpi.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class HomeCompositeNetworkScopeTest {
    @Test
    fun `changed run does not publish late network measurements`() =
        runTest {
            val fixture = HomeNetworkScopeFixture(backgroundScope)
            val outcome = fixture.finalize(networkChanged = true)
            assertNull(outcome.networkCharacter)
            assertEquals(0, fixture.source.networkCalls)
        }

    @Test
    fun `stable original scope retains network measurements and recommendation`() =
        runTest {
            val outcome = HomeNetworkScopeFixture(backgroundScope).finalize()
            assertNotNull(outcome.networkCharacter)
            assertNotNull(outcome.bufferbloat)
            assertNotNull(outcome.dnsCharacterization)
            assertEquals("audit", outcome.recommendedSessionId)
        }

    @Test
    fun `missing original physical epoch suppresses network authority and preserves local evidence`() =
        runTest {
            val fixture = HomeNetworkScopeFixture(backgroundScope, FakeDiagnosticsNetworkEpochProvider(null))
            val outcome = fixture.finalize()
            assertEquals(0, fixture.source.networkCalls)
            assertNull(outcome.fingerprintHash)
            assertFalse(outcome.actionable)
            assertNull(outcome.recommendedSessionId)
            assertEquals(2, outcome.routingSanity?.totalConfiguredApps)
            assertEquals(1, outcome.installedVpnDetectorCount)
        }

    @Test
    fun `physical A B A during DNS drops all earlier network augmentations`() =
        runTest {
            val fixture = HomeNetworkScopeFixture(backgroundScope)
            fixture.source.afterDns = { fixture.epoch.epoch = FakeDiagnosticsNetworkEpoch(3) }
            val outcome = fixture.finalize()
            assertNull(outcome.networkCharacter)
            assertNull(outcome.bufferbloat)
            assertNull(outcome.dnsCharacterization)
            assertNull(outcome.recommendedSessionId)
            assertFalse(outcome.actionable)
            assertEquals(2, outcome.routingSanity?.totalConfiguredApps)
        }

    @Test
    fun `fingerprint change during DNS drops network recommendation`() =
        runTest {
            val fixture = HomeNetworkScopeFixture(backgroundScope)
            fixture.source.afterDns = {
                fixture.fingerprint.fingerprint = fixture.fingerprint.fingerprint?.copy(transport = "cellular")
            }
            val outcome = fixture.finalize()
            assertNull(outcome.dnsCharacterization)
            assertNull(outcome.recommendedSessionId)
        }

    @Test
    fun `missing original fingerprint cannot regain authority from a later snapshot`() =
        runTest {
            val fixture =
                HomeNetworkScopeFixture(backgroundScope, fingerprint = MutableNetworkFingerprintProvider(null))
            fixture.fingerprint.fingerprint = MutableNetworkFingerprintProvider().fingerprint
            val outcome = fixture.finalize()
            assertNull(outcome.networkCharacter)
            assertEquals(0, fixture.source.networkCalls)
        }

    @Test
    fun `missing run scope is not replaced by a finalization snapshot`() =
        runTest {
            val fixture = HomeNetworkScopeFixture(backgroundScope)
            val outcome = fixture.finalize(networkScope = null)
            assertNull(outcome.networkCharacter)
            assertFalse(outcome.actionable)
        }

    @Test
    fun `cancellation during DNS is not converted into a completed partial outcome`() =
        runTest {
            val fixture = HomeNetworkScopeFixture(backgroundScope)
            fixture.source.afterDns = { throw CancellationException("Run cancelled") }
            try {
                fixture.finalize()
                fail("Cancellation must propagate")
            } catch (cancelled: CancellationException) {
                assertEquals("Run cancelled", cancelled.message)
            }
            assertFalse(fixture.hasCompletedOutcome)
        }

    @Test
    fun `previous run on another network is not a regression baseline`() =
        runTest {
            val fixture = HomeNetworkScopeFixture(backgroundScope)
            val outcome = fixture.finalize(previousOutcome = previousHomeScopeOutcome("another-network"))
            assertNull(outcome.regressionDelta)
        }

    @Test
    fun `previous run on the same network remains a regression baseline`() =
        runTest {
            val fixture = HomeNetworkScopeFixture(backgroundScope)
            val previous = previousHomeScopeOutcome(fixture.fingerprint.capture()?.scopeKey())
            val outcome = fixture.finalize(previousOutcome = previous)
            assertEquals(listOf("automatic_audit"), outcome.regressionDelta?.newlyRecoveredStageKeys)
        }

    @Test
    fun `changed network retains a valid durable local detection receipt`() =
        runTest {
            val fixture = HomeNetworkScopeFixture(backgroundScope, detection = mixedHomeScopeDetectionOutcome())
            val outcome = fixture.finalize(networkChanged = true)
            assertEquals(DiagnosticsHomeDetectionVerdict.NEEDS_REVIEW, outcome.detectionVerdict)
            assertEquals(1, outcome.detectionSignalCount)
            assertEquals(listOf("Local app"), outcome.detectionFindings)
            assertEquals(emptyList<String>(), outcome.detectionNetworkFindings)
            assertEquals(listOf("LOCAL_INVENTORY"), outcome.detectionDecisionSignals.map { it.scope })
        }
}
