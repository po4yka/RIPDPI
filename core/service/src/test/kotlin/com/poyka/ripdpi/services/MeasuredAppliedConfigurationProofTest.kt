package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProfileUtilityReference
import com.poyka.ripdpi.data.ProfileUtilitySelectionPayload
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RuntimeActivationReceipt
import com.poyka.ripdpi.data.RuntimeAppliedIntent
import com.poyka.ripdpi.data.RuntimeConfigurationApplyReason
import com.poyka.ripdpi.data.RuntimeConfigurationAttempt
import com.poyka.ripdpi.data.RuntimeConfigurationDns
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import com.poyka.ripdpi.data.RuntimeConfigurationStrategy
import com.poyka.ripdpi.data.testProfileRecovery
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real registry, claim, checked composite ACK and publication; candidate input is a controlled unit fixture. */
class MeasuredAppliedConfigurationProofTest {
    @Test fun `owned command and catalog changes retain measured proof through exact native ACK`() =
        runTest {
            val f = Fixture()
            val attempt = f.bind()
            assertTrue(f.consumer.acknowledge(attempt, f.applied(attempt), f.proof))
            assertEquals(
                listOf(f.reference),
                checkNotNull(f.authority.states.value).profileUtility.recents.map { it.reference },
            )
            var published = false
            assertTrue(f.consumer.publishIfCurrent(attempt) { published = true })
            assertTrue(published)
            val committed = f.authority.states.value
            assertTrue(f.consumer.acknowledge(attempt, f.applied(attempt), f.proof))
            assertEquals(committed, f.authority.states.value)
        }

    @Test fun `different captured requested policy rejects before native claim`() =
        runTest {
            val f = Fixture()
            val bound = f.prepare()
            val attempt = f.attempt(bound)
            val changed = RuntimeConfigurationIdentityFactory().capture(listOf("different-policy"), emptyList())
            val before = f.authority.states.value
            assertTrue(runCatching { f.consumer.bind(attempt, changed) }.isFailure)
            assertEquals(before, f.authority.states.value)
            assertTrue(checkNotNull(f.authority.states.value).profileUtility.recents.isEmpty())
        }

    @Test fun `actual consumed different endpoint rejects ACK even after saved policy returns to measured input`() =
        runTest {
            val f = Fixture()
            val attempt = f.bind()
            val before = f.authority.states.value
            val different =
                CandidateConfigurationProofs.relay(
                    sampleResolvedRelayConfig(profileId = "actual").copy(server = "other.example"),
                )
            assertFalse(f.consumer.acknowledge(attempt, f.applied(attempt), different))
            assertFalse(f.consumer.acknowledge(attempt, f.applied(attempt), null))
            assertEquals(before, f.authority.states.value)
            assertTrue(checkNotNull(f.authority.states.value).profileUtility.recents.isEmpty())
            assertFalse(f.consumer.publishIfCurrent(attempt) { error("Unacknowledged configuration cannot publish") })
        }

    @Test fun `physical scope change between native claim and ACK keeps history unchanged`() =
        runTest {
            val f = Fixture()
            val attempt = f.bind()
            f.externalMatches = false
            val before = f.authority.states.value
            assertFalse(f.consumer.acknowledge(attempt, f.applied(attempt), f.proof))
            assertEquals(before, f.authority.states.value)
            assertTrue(checkNotNull(f.authority.states.value).profileUtility.recents.isEmpty())
        }

    @Test fun `failed checked termination retires proof and rejects late ACK without changing durable state`() =
        runTest {
            val f = Fixture()
            val attempt = f.bind()
            val before = f.authority.states.value
            f.disk.fail = true
            assertTrue(runCatching { f.consumer.failed(attempt) }.isFailure)
            f.disk.fail = false
            assertFalse(f.measured.allows(attempt, f.requested.identity))
            assertFalse(f.consumer.acknowledge(attempt, f.applied(attempt), f.proof))
            assertFalse(f.consumer.publishIfCurrent(attempt) { error("Failed attempt cannot publish") })
            assertEquals(before, f.authority.states.value)
        }

    private class Fixture {
        val disk = Disk()
        val authority =
            com.poyka.ripdpi.data
                .PauseIntentAuthority(
                    disk,
                    object : com.poyka.ripdpi.data.PauseClock {
                        override fun read() =
                            com.poyka.ripdpi.data
                                .PauseClockReading(1_800_000_000_000, 10_000, 7)
                    },
                    com.poyka.ripdpi.data
                        .RuntimeIntentLinearizer(),
                ).also { it.initializeAfterMigration() }
        val reference = ProfileUtilityReference.NativeRelay("actual")
        val proof = CandidateConfigurationProofs.relay(sampleResolvedRelayConfig(profileId = "actual"))
        val requested = sampleResolution(Mode.Proxy).requestedConfiguration
        var externalMatches = true
        val measured =
            MeasuredActivationRegistry(
                authority,
                testProfileRecovery(),
                RequestedRuntimeConfigurationSource { _, _, _ -> requested },
                TestAppSettingsRepository(),
            )
        val consumer = RuntimeAppliedReceiptConsumer(authority, measured)
        val selection = RuntimeConfigurationSelection("native", relayKind = "vless_reality", profileId = "actual")

        init {
            authority.profileUtility.replaceCatalog(setOf(reference))
        }

        suspend fun prepare(): RuntimeActivationReceipt {
            val captured = authority.snapshotAuthority()
            val generation = checkNotNull(authority.states.value).profileUtility.catalogGeneration
            val profile =
                checkNotNull(authority.reserveMeasuredActivation(captured, generation, reference, "measured-command"))
            authority.profileUtility.invalidateCatalog()
            val postGeneration = authority.profileUtility.replaceCatalog(setOf(reference))
            val lease =
                object : MeasuredProfileSelectionLease {
                    override val reference = this@Fixture.reference
                    override val catalogGeneration = generation
                    override val expectedAuthority = captured
                    override val payload =
                        ProfileUtilitySelectionPayload.Native(
                            RelayProfileRecord(id = "actual", kind = "vless_reality"),
                            RelayCredentialRecord(profileId = "actual"),
                        )
                    override val configurationProof = proof

                    override suspend fun refreshEnvironment() = externalMatches

                    override suspend fun payloadMatches() = true

                    override fun environmentMatchesNow() = externalMatches

                    override suspend fun refreshExternalEnvironment() = externalMatches

                    override fun externalEnvironmentMatchesNow() = externalMatches
                }
            measured.register(profile, lease, postGeneration)
            return checkNotNull(measured.bind(profile, Mode.Proxy))
        }

        fun attempt(bound: RuntimeActivationReceipt) =
            RuntimeConfigurationAttempt(
                "measured-runtime",
                1,
                Mode.Proxy,
                selection,
                RuntimeConfigurationApplyReason.InitialStart,
                RuntimeAppliedIntent.Activation(bound),
                checkNotNull(authority.states.value).profileUtility.catalogGeneration,
            )

        suspend fun bind() = attempt(prepare()).also { consumer.bind(it, requested.identity) }

        fun applied(attempt: RuntimeConfigurationAttempt) =
            AppliedRuntimeConfiguration(
                attempt.runtimeId,
                attempt.revision,
                100,
                Mode.Proxy,
                selection,
                selection,
                RuntimeConfigurationDns("plain", "system"),
                RuntimeConfigurationStrategy(false),
                attempt.reason,
            )
    }

    private class Disk : com.poyka.ripdpi.data.PauseAuthorityPersistence {
        var state: com.poyka.ripdpi.data.PauseAuthorityState? = null
        var fail = false

        override fun read() = state

        override fun commit(state: com.poyka.ripdpi.data.PauseAuthorityState) {
            check(!fail) { "Checked persistence failed" }
            this.state = state
        }
    }
}
