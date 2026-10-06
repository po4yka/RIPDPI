package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositeRuntimePersistenceTest {
    @Test
    fun `matching resume records actual use and consumes pause in one checked commit`() {
        val fixture = Fixture()
        val intent = fixture.pause()
        val commits = fixture.disk.commits
        assertTrue(fixture.authority.acknowledgeApplied(fixture.resume(intent), fixture.receipt()))
        assertEquals(commits + 1, fixture.disk.commits)
        assertNull(fixture.disk.state?.pause)
        assertEquals(DesiredRuntimeState.Running, fixture.disk.state?.desired)
        assertEquals(Mode.Proxy.preferenceValue, fixture.disk.state?.desiredMode)
        assertEquals(
            listOf(fixture.reference),
            fixture.disk.state
                ?.profileUtility
                ?.recents
                ?.map { it.reference },
        )
        assertEquals(fixture.disk.state, fixture.authority.states.value)
    }

    @Test
    fun `failed composite commit publishes neither pause completion nor recent`() {
        val fixture = Fixture()
        val intent = fixture.pause()
        val before = fixture.disk.state
        fixture.disk.fail = true
        assertTrue(
            runCatching {
                fixture.authority.acknowledgeApplied(fixture.resume(intent), fixture.receipt())
            }.isFailure,
        )
        assertEquals(before, fixture.disk.state)
        assertEquals(before, fixture.authority.states.value)
    }

    @Test
    fun `newer Stop rejects stale actual acknowledgment and preserves utility`() {
        val fixture = Fixture()
        val intent = fixture.pause()
        fixture.authority.reserveStop()
        val before = fixture.disk.state
        assertFalse(fixture.authority.acknowledgeApplied(fixture.resume(intent), fixture.receipt()))
        assertEquals(before, fixture.disk.state)
    }

    @Test
    fun `catalog deletion rejects late acknowledgment instead of resurrecting deleted profile`() {
        val fixture = Fixture()
        val intent = fixture.pause()
        val receipt = fixture.receipt()
        fixture.authority.profileUtility.replaceCatalog(emptySet())
        val before = fixture.disk.state
        assertTrue(
            runCatching {
                fixture.authority.acknowledgeApplied(fixture.resume(intent), receipt)
            }.isFailure,
        )
        assertEquals(before, fixture.disk.state)
    }

    @Test
    fun `identical resume acknowledgment after commit is idempotent and conflict is rejected`() {
        val fixture = Fixture()
        val intent = fixture.pause()
        val receipt = fixture.receipt()
        assertTrue(fixture.authority.acknowledgeApplied(fixture.resume(intent), receipt))
        val before = fixture.disk.state
        val commits = fixture.disk.commits
        assertTrue(fixture.authority.acknowledgeApplied(fixture.resume(intent), receipt))
        assertEquals(commits, fixture.disk.commits)
        assertTrue(
            runCatching {
                fixture.authority.acknowledgeApplied(
                    fixture.resume(intent),
                    receipt.copy(appliedAtMillis = 201),
                )
            }.isFailure,
        )
        assertEquals(before, fixture.disk.state)
    }

    @Test
    fun `pending catalog blocks ACK and same ID committed edit fences the old revision`() {
        val fixture = Fixture()
        val intent = fixture.pause()
        val old = fixture.receipt()
        fixture.authority.profileUtility.invalidateCatalog()
        val pending = fixture.disk.state
        assertFalse(checkNotNull(pending).profileUtility.catalogReady)
        assertTrue(
            runCatching {
                fixture.authority.acknowledgeApplied(fixture.resume(intent), old)
            }.isFailure,
        )
        assertEquals(pending, fixture.disk.state)
        fixture.authority.profileUtility.replaceCatalog(setOf(fixture.reference))
        assertTrue(checkNotNull(fixture.disk.state).profileUtility.catalogReady)
        assertTrue(
            runCatching {
                fixture.authority.acknowledgeApplied(fixture.resume(intent), old)
            }.isFailure,
        )
        assertTrue(fixture.authority.acknowledgeApplied(fixture.resume(intent), fixture.receipt()))
    }

    @Test
    fun `favorite commit failure leaves intent and utility unchanged and successful favorite retains intent`() {
        val fixture = Fixture()
        val intent = fixture.pause()
        val before = fixture.disk.state
        val runtimeSnapshot = fixture.authority.snapshotAuthority()
        fixture.disk.fail = true
        assertTrue(runCatching { fixture.authority.profileUtility.setFavorite(fixture.reference, true) }.isFailure)
        assertEquals(before, fixture.disk.state)
        assertEquals(before, fixture.authority.states.value)
        fixture.disk.fail = false
        fixture.authority.profileUtility.setFavorite(fixture.reference, true)
        assertEquals(runtimeSnapshot, fixture.authority.snapshotAuthority())
        assertEquals(
            intent.token,
            fixture.disk.state
                ?.pause
                ?.token,
        )
        assertEquals(setOf(fixture.reference), checkNotNull(fixture.disk.state).profileUtility.favorites)
    }

    private class Fixture {
        val disk = MemoryDisk()
        val authority =
            PauseIntentAuthority(
                disk,
                object : PauseClock {
                    override fun read() = PauseClockReading(1_800_000_000_000, 10_000, 7)
                },
                RuntimeIntentLinearizer(),
            )
        val reference = ProfileUtilityReference.NativeRelay("actual")
        private var resumeReceipt: RuntimeActivationReceipt? = null

        init {
            authority.initializeAfterMigration()
            authority.profileUtility.replaceCatalog(setOf(reference))
        }

        fun pause(): PauseIntent =
            authority.begin(Mode.Proxy, 300_000, authority.snapshotAuthority()).also {
                assertTrue(authority.transition(it, PausePhase.Paused, null))
                resumeReceipt = checkNotNull(authority.claimResume(it, true))
                assertTrue(
                    authority.claimActivation(
                        checkNotNull(resumeReceipt),
                        RuntimeAppliedUseIdentity("runtime", 1, Mode.Proxy.preferenceValue),
                    ),
                )
            }

        fun resume(intent: PauseIntent) = RuntimeAppliedIntent.Resume(intent, checkNotNull(resumeReceipt))

        fun receipt() =
            RuntimeAppliedUseReceipt(
                RuntimeAppliedUseIdentity("runtime", 1, Mode.Proxy.preferenceValue),
                listOf(reference),
                200,
                checkNotNull(authority.states.value).profileUtility.catalogGeneration,
                true,
                "0".repeat(64),
            )
    }

    private class MemoryDisk : PauseAuthorityPersistence {
        var state: PauseAuthorityState? = null
        var fail = false
        var commits = 0

        override fun read() = state

        override fun commit(state: PauseAuthorityState) {
            check(!fail) { "Checked commit failed" }
            this.state = state
            commits++
        }
    }
}
