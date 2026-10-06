package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCommandRecoveryTest {
    @Test fun `claimed profile activation roundtrips and reconstruction terminates it`() {
        val f = Fixture()
        val mutation =
            f.a.invalidateForMutation(ProfileMutationOrigin.ExplicitActivation, "activate", f.a.reference())
                as ProfileMutationOutcome.Reserved
        val receipt = checkNotNull(f.a.bindProfileActivation(mutation.receipt as ProfileActivationReceipt, Mode.Proxy))
        assertTrue(f.a.claimActivation(receipt, identity))
        val state = checkNotNull(f.disk.state)
        assertEquals(DesiredRuntimeState.Stopped, state.desired)
        assertNull(state.desiredMode)
        val decoded = RuntimePersistenceCodec.decode(RuntimePersistenceCodec.encode(state)).first
        assertEquals(state, decoded)
        f.disk.state = decoded
        val restarted = PauseIntentAuthority(f.disk, clock, RuntimeIntentLinearizer())
        restarted.initializeAfterMigration()
        assertFalse(restarted.claimActivation(receipt, identity))
        assertEquals(
            RuntimeActivationPhase.Terminated,
            restarted.states.value
                ?.command
                ?.phase,
        )
    }

    @Test fun `failed reconstruction commit is retried and old claim never survives initialization`() {
        val f = Fixture()
        val receipt = f.a.reserveStart(Mode.Proxy)
        assertTrue(f.a.claimActivation(receipt, identity))
        val restarted = PauseIntentAuthority(f.disk, clock, RuntimeIntentLinearizer())
        f.disk.fail = true
        assertTrue(runCatching { restarted.initializeAfterMigration() }.isFailure)
        f.disk.fail = false
        restarted.initializeAfterMigration()
        assertEquals(
            RuntimeActivationPhase.Terminated,
            restarted.states.value
                ?.command
                ?.phase,
        )
        assertFalse(restarted.claimActivation(receipt, identity))
    }

    @Test fun `failed resume can retry but its terminated receipt cannot claim or ACK`() {
        val f = Fixture()
        val pause = f.a.begin(Mode.Proxy, 300_000, f.a.snapshotAuthority())
        f.a.transition(pause, PausePhase.Paused, null)
        val first = checkNotNull(f.a.claimResume(pause, true))
        assertTrue(f.a.claimActivation(first, identity))
        assertTrue(f.a.terminateActivation(first, identity))
        val retry = checkNotNull(f.a.claimResume(pause, true))
        assertFalse(first.commandId == retry.commandId)
        assertFalse(f.a.claimActivation(first, identity))
        assertFalse(f.a.acknowledgeApplied(RuntimeAppliedIntent.Resume(pause, first), receipt(f.a)))
        assertTrue(f.a.claimActivation(retry, identity))
        assertTrue(f.a.acknowledgeApplied(RuntimeAppliedIntent.Resume(pause, retry), receipt(f.a)))
        assertNull(f.a.snapshot())
    }

    @Test fun `legacy paused authority retains its lease and may explicitly resume`() {
        val f = Fixture()
        val pause = f.a.begin(Mode.Proxy, 300_000, f.a.snapshotAuthority())
        f.a.transition(pause, PausePhase.Paused, null)
        f.disk.state = checkNotNull(f.disk.state).copy(command = null)
        val migrated = PauseIntentAuthority(f.disk, clock, RuntimeIntentLinearizer())
        migrated.initializeAfterMigration()
        assertEquals(pause.token, migrated.snapshot()?.token)
        val resume = checkNotNull(migrated.claimResume(pause, true))
        assertTrue(migrated.claimActivation(resume, identity))
        assertTrue(migrated.acknowledgeApplied(RuntimeAppliedIntent.Resume(pause, resume), receipt(migrated)))
    }

    @Test fun `legacy running can recover only its exact captured mode and stopped cannot forge recovery`() {
        val f = Fixture()
        f.disk.state =
            checkNotNull(f.disk.state).copy(
                desired = DesiredRuntimeState.Running,
                desiredMode = Mode.Proxy.preferenceValue,
                command = null,
            )
        val migrated = PauseIntentAuthority(f.disk, clock, RuntimeIntentLinearizer())
        migrated.initializeAfterMigration()
        val original = migrated.snapshotAuthority()
        assertNull(migrated.authorizeRecovery(Mode.VPN, original))
        val recovery = checkNotNull(migrated.authorizeRecovery(Mode.Proxy, original))
        assertEquals(RuntimeCommandOrigin.Recovery(null, null), recovery.origin)
        assertNull(migrated.authorizeRecovery(Mode.Proxy, original))
        assertTrue(migrated.claimActivation(recovery, identity))
        assertTrue(migrated.acknowledgeApplied(RuntimeAppliedIntent.Recovery(recovery, Mode.Proxy), receipt(migrated)))
        migrated.reserveStop()
        assertNull(migrated.authorizeRecovery(Mode.Proxy, migrated.snapshotAuthority()))
        assertFalse(migrated.acknowledgeApplied(RuntimeAppliedIntent.Recovery(recovery, Mode.Proxy), receipt(migrated)))
    }

    @Test fun `same generation recovered ACK cannot revalidate an older publication permit`() {
        val f = Fixture()
        val initial = f.a.reserveStart(Mode.Proxy)
        assertTrue(f.a.claimActivation(initial, identity))
        assertTrue(f.a.acknowledgeApplied(RuntimeAppliedIntent.Activation(initial), receipt(f.a)))
        val oldPermit = checkNotNull(f.a.publicationPermit(initial, identity))
        val recovery = checkNotNull(f.a.authorizeRecovery(Mode.Proxy, f.a.snapshotAuthority()))
        assertEquals(RuntimeCommandOrigin.Recovery(initial.commandId, identity), recovery.origin)
        val recoveredIdentity = identity.copy(runtimeId = "recovered-runtime")
        assertTrue(f.a.claimActivation(recovery, recoveredIdentity))
        assertTrue(
            f.a.acknowledgeApplied(
                RuntimeAppliedIntent.Recovery(recovery, Mode.Proxy),
                receipt(f.a).copy(identity = recoveredIdentity),
            ),
        )
        assertEquals(initial.authority, recovery.authority)
        assertFalse(f.a.allowsPublication(oldPermit))
        assertNull(f.a.publicationPermit(initial, identity))
        assertTrue(f.a.allowsPublication(checkNotNull(f.a.publicationPermit(recovery, recoveredIdentity))))
    }

    @Test fun `real legacy pause without desired mode survives claimed resume reconstruction`() {
        val f = Fixture()
        val pause = f.a.begin(Mode.Proxy, 300_000, f.a.snapshotAuthority())
        f.a.transition(pause, PausePhase.Paused, null)
        val legacyState = checkNotNull(f.disk.state).copy(desiredMode = null, command = null)
        val json = com.poyka.ripdpi.serialization.RipDpiContractJson
        val envelope =
            json.parseToJsonElement(RuntimePersistenceCodec.encode(legacyState))
                as kotlinx.serialization.json.JsonObject
        val state = envelope.getValue("state") as kotlinx.serialization.json.JsonObject
        val legacyBytes =
            kotlinx.serialization.json
                .JsonObject(
                    state.filterKeys {
                        it != "command" && it != "profileUtility" && it != "desiredMode"
                    },
                ).toString()
        f.disk.state = RuntimePersistenceCodec.decode(legacyBytes).first
        val migrated = PauseIntentAuthority(f.disk, clock, RuntimeIntentLinearizer())
        migrated.initializeAfterMigration()
        val resume = checkNotNull(migrated.claimResume(pause, true))
        assertTrue(migrated.claimActivation(resume, identity))
        val claimed = checkNotNull(f.disk.state)
        assertEquals(claimed, RuntimePersistenceCodec.decode(RuntimePersistenceCodec.encode(claimed)).first)
        val reconstructed = PauseIntentAuthority(f.disk, clock, RuntimeIntentLinearizer())
        reconstructed.initializeAfterMigration()
        assertEquals(pause.token, reconstructed.snapshot()?.token)
        assertNull(reconstructed.activationReceiptFromEnvelope(resume.envelope()))
    }

    @Test fun `continuation cannot acknowledge a first claim and successful continuation never adds history`() {
        val f = Fixture()
        val initial = f.a.reserveStart(Mode.Proxy)
        assertTrue(f.a.claimActivation(initial, identity))
        assertFalse(f.a.acknowledgeApplied(RuntimeAppliedIntent.Continuation(initial, identity), receipt(f.a)))
        assertTrue(f.a.acknowledgeApplied(RuntimeAppliedIntent.Activation(initial), receipt(f.a)))
        assertFalse(f.a.acknowledgeApplied(RuntimeAppliedIntent.Continuation(initial, identity), receipt(f.a)))
        val next = identity.copy(revision = 2)
        assertTrue(f.a.claimContinuation(initial, identity, next))
        val current = receipt(f.a).copy(identity = next)
        assertFalse(
            f.a.acknowledgeApplied(
                RuntimeAppliedIntent.Continuation(initial, identity),
                current.copy(recordsUse = true),
            ),
        )
        assertTrue(f.a.acknowledgeApplied(RuntimeAppliedIntent.Continuation(initial, identity), current))
    }

    @Test fun `old revision termination cannot kill a newer claimed continuation`() {
        val f = Fixture()
        val initial = f.a.reserveStart(Mode.Proxy)
        assertTrue(f.a.claimActivation(initial, identity))
        assertTrue(f.a.acknowledgeApplied(RuntimeAppliedIntent.Activation(initial), receipt(f.a)))
        assertFalse(f.a.acknowledgeApplied(RuntimeAppliedIntent.Continuation(initial, identity), receipt(f.a)))
        val next = identity.copy(revision = 2)
        assertTrue(f.a.claimContinuation(initial, identity, next))
        assertFalse(f.a.terminateActivation(initial, identity))
        assertTrue(
            f.a.acknowledgeApplied(
                RuntimeAppliedIntent.Continuation(initial, identity),
                receipt(f.a).copy(identity = next),
            ),
        )
    }

    @Test fun `rejected owned recovery retries with new command and never revives an older publication permit`() {
        val f = Fixture()
        val initial = f.a.reserveStart(Mode.Proxy)
        assertTrue(f.a.claimActivation(initial, identity))
        assertTrue(f.a.acknowledgeApplied(RuntimeAppliedIntent.Activation(initial), receipt(f.a)))
        val permit = checkNotNull(f.a.publicationPermit(initial, identity))
        val first = checkNotNull(f.a.authorizeRecovery(Mode.Proxy, f.a.snapshotAuthority()))
        val retry = checkNotNull(f.a.authorizeRecovery(Mode.Proxy, f.a.snapshotAuthority()))
        assertFalse(first.commandId == retry.commandId)
        assertEquals(first.authority, retry.authority)
        assertNull(f.a.activationReceiptFromEnvelope(first.envelope()))
        assertFalse(f.a.allowsPublication(permit))
        val next = identity.copy(runtimeId = "retry-runtime")
        assertTrue(f.a.claimActivation(retry, next))
        assertTrue(
            f.a.acknowledgeApplied(
                RuntimeAppliedIntent.Recovery(retry, Mode.Proxy),
                receipt(f.a).copy(identity = next),
            ),
        )
        assertFalse(f.a.allowsPublication(permit))
    }

    @Test fun `terminated recovery retains its exact positive predecessor for retry`() {
        val f = Fixture()
        val initial = f.a.reserveStart(Mode.Proxy)
        assertTrue(f.a.claimActivation(initial, identity))
        assertTrue(f.a.acknowledgeApplied(RuntimeAppliedIntent.Activation(initial), receipt(f.a)))
        val failed = checkNotNull(f.a.authorizeRecovery(Mode.Proxy, f.a.snapshotAuthority()))
        val attempt = identity.copy(runtimeId = "failed-recovery")
        assertTrue(f.a.claimActivation(failed, attempt))
        assertTrue(f.a.terminateActivation(failed, attempt))
        val state = checkNotNull(f.disk.state)
        f.disk.state = RuntimePersistenceCodec.decode(RuntimePersistenceCodec.encode(state)).first
        val reconstructed = PauseIntentAuthority(f.disk, clock, RuntimeIntentLinearizer())
        reconstructed.initializeAfterMigration()
        val retry = checkNotNull(reconstructed.authorizeRecovery(Mode.Proxy, reconstructed.snapshotAuthority()))
        assertFalse(retry.commandId == failed.commandId)
        assertEquals(identity, (retry.origin as RuntimeCommandOrigin.Recovery).priorAppliedIdentity)
        reconstructed.reserveStop()
        assertNull(reconstructed.authorizeRecovery(Mode.Proxy, reconstructed.snapshotAuthority()))
    }

    @Test fun `new pause or unacknowledged user start cannot borrow old recovery evidence`() {
        val f = Fixture()
        val initial = f.a.reserveStart(Mode.Proxy)
        assertTrue(f.a.claimActivation(initial, identity))
        assertTrue(f.a.acknowledgeApplied(RuntimeAppliedIntent.Activation(initial), receipt(f.a)))
        checkNotNull(f.a.authorizeRecovery(Mode.Proxy, f.a.snapshotAuthority()))
        val old = f.a.snapshotAuthority()
        f.a.begin(Mode.Proxy, 300_000, old)
        assertNull(f.a.authorizeRecovery(Mode.Proxy, old))
        assertNull(f.a.authorizeRecovery(Mode.Proxy, f.a.snapshotAuthority()))
        f.a.reserveStart(Mode.Proxy)
        assertNull(f.a.authorizeRecovery(Mode.Proxy, f.a.snapshotAuthority()))
    }

    private class Fixture {
        val disk = Disk()
        val a = PauseIntentAuthority(disk, clock, RuntimeIntentLinearizer()).also { it.initializeAfterMigration() }
    }

    private class Disk : PauseAuthorityPersistence {
        var state: PauseAuthorityState? = null
        var fail = false

        override fun read() = state

        override fun commit(state: PauseAuthorityState) {
            check(!fail)
            this.state = state
        }
    }

    companion object {
        val clock =
            object : PauseClock {
                override fun read() = PauseClockReading(1_800_000_000_000, 10_000, 7)
            }
        val identity = RuntimeAppliedUseIdentity("runtime", 1, Mode.Proxy.preferenceValue)

        fun receipt(a: PauseIntentAuthority) =
            RuntimeAppliedUseReceipt(
                identity,
                emptyList(),
                200,
                checkNotNull(a.states.value).profileUtility.catalogGeneration,
                false,
                "0".repeat(64),
            )
    }
}
