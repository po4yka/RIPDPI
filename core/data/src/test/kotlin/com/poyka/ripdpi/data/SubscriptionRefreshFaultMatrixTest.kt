package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.awg.AwgSubscriptionProfile
import com.poyka.ripdpi.data.awg.SavedAwgProfile
import com.poyka.ripdpi.data.awg.requireRuntimeReady
import com.poyka.ripdpi.serialization.RipDpiContractJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class SubscriptionRefreshFaultMatrixTest(
    private val cut: SubscriptionRefreshFaultCut,
    private val cancellation: Boolean,
    private val cold: Boolean,
) {
    @Test
    fun `interrupted refresh preserves its exact after-image through recovery`() =
        runTest {
            val scenario = SubscriptionFaultScenario().apply { seed() }
            val observed = mutableListOf<List<ProxyGroup>>()
            val warmObserver =
                if (cold) {
                    null
                } else {
                    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                        scenario.groups.groups().collect { observed += it }
                    }
                }
            val fault = SubscriptionRefreshFault(cut, cancellation)
            fault.install(scenario.fixture)

            val failure = runCatching { scenario.refresh() }.exceptionOrNull()

            assertTrue("The selected persistence cut must be reached", fault.fired)
            assertEquals(fault.failure.javaClass, failure?.javaClass)
            assertEquals(fault.failure.message, failure?.message)
            scenario.assertInterrupted(fault)
            val beforeRecovery = fault.expectedObserved(scenario.before.groups, cold, recovered = false)
            assertEquals(beforeRecovery, observed)
            fault.clear(scenario.fixture)

            val recovery = scenario.recovery(cold)
            val coldObserver =
                if (cold) {
                    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                        recovery.groups.groups().collect { observed += it }
                    }
                } else {
                    null
                }
            if (cold) assertTrue("Cold observers must wait for publication", observed.isEmpty())
            val interruptedWrites = scenario.writeCounts()
            recovery.mutations.recover()
            if (cut.completed) assertEquals(interruptedWrites, scenario.writeCounts())

            val expectedObserved = fault.expectedObserved(scenario.before.groups, cold, recovered = true)
            assertEquals("Recovery must publish without a repository list call", expectedObserved, observed)
            scenario.assertRecovered(fault, recovery)
            val recovered = scenario.snapshot()
            val writes = scenario.writeCounts()
            val generation = scenario.fixture.mutationGeneration.generation.value

            recovery.mutations.recover()
            recovery.mutations.recover()

            assertEquals(recovered, scenario.snapshot())
            assertEquals(writes, scenario.writeCounts())
            assertEquals(generation, scenario.fixture.mutationGeneration.generation.value)
            assertEquals(expectedObserved, observed)
            val edited =
                checkNotNull(recovery.groups.updateGroup(scenario.expected.id) { it.copy(name = "Later edit") })
            val afterEdit = scenario.snapshot()
            assertEquals(edited, afterEdit.groups.first())
            assertNotEquals(recovered.groups.first().ownershipGeneration, edited.ownershipGeneration)
            assertEquals(recovered.groups.first().subscription, edited.subscription)
            val writesAfterEdit = scenario.writeCounts()
            recovery.mutations.recover()
            recovery.mutations.recover()
            assertEquals(afterEdit, scenario.snapshot())
            assertEquals(writesAfterEdit, scenario.writeCounts())
            warmObserver?.cancelAndJoin()
            coldObserver?.cancelAndJoin()
        }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}, cancellation={1}, cold={2}")
        fun cases(): List<Array<Any>> =
            SubscriptionRefreshFaultCut.entries.flatMap { cut ->
                listOf(false, true).flatMap { cancellation ->
                    listOf(false, true).map { cold -> arrayOf<Any>(cut, cancellation, cold) }
                }
            }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionRefreshRecoveryEditTest {
    @Test
    fun `competing edit waits for replay and survives every later recovery`() =
        runTest {
            val scenario = SubscriptionFaultScenario().apply { seed() }
            val fault = SubscriptionRefreshFault(SubscriptionRefreshFaultCut.AFTER_FIRST_ROW, cancellation = false)
            fault.install(scenario.fixture)
            assertEquals(fault.failure.message, runCatching { scenario.refresh() }.exceptionOrNull()?.message)
            assertTrue(fault.fired)
            val pending = checkNotNull(scenario.fixture.journal.pending())
            val accepted = checkNotNull(fault.intent).groups.single { it.id == scenario.expected.id }
            fault.clear(scenario.fixture)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            scenario.fixture.awgProfiles.beforeUpsert = {
                entered.complete(Unit)
                release.await()
            }
            val replay = async { scenario.mutations.recover() }
            withTimeout(5_000L) { entered.await() }
            val edit =
                async {
                    scenario.groups.updateGroup(scenario.expected.id) { it.copy(name = "Edited after recovery") }
                }
            runCurrent()

            assertFalse("Group edits must wait for the pending refresh", edit.isCompleted)
            assertEquals(pending, scenario.fixture.journal.pending())
            assertFalse(checkNotNull(scenario.fixture.pauseAuthority.states.value).profileUtility.catalogReady)
            release.complete(Unit)
            replay.await()
            val edited = checkNotNull(edit.await())

            assertEquals(accepted.subscription, edited.subscription)
            assertEquals("Edited after recovery", edited.name)
            assertNotEquals(accepted.ownershipGeneration, edited.ownershipGeneration)
            assertEquals(edited, scenario.snapshot().groups.single { it.id == edited.id })
            assertEquals(scenario.before.groups.last(), scenario.snapshot().groups.last())
            assertNull(scenario.fixture.journal.pending())
            assertEquals(1, scenario.preparations)
            assertEquals(1, scenario.fixture.prepareAttempts)
            scenario.fixture.awgProfiles.beforeUpsert = null
            val committed = scenario.snapshot()
            val writes = scenario.writeCounts()
            val generation = scenario.fixture.mutationGeneration.generation.value

            scenario.mutations.recover()
            scenario.mutations.recover()

            assertEquals(committed, scenario.snapshot())
            assertEquals(writes, scenario.writeCounts())
            assertEquals(generation, scenario.fixture.mutationGeneration.generation.value)
        }
}

enum class SubscriptionRefreshFaultCut(
    val credentialsWritten: Int,
    val rowsWritten: Int,
    val persistsGroup: Boolean = false,
    val publishesGroup: Boolean = false,
    val completed: Boolean = false,
) {
    BEFORE_PREPARE(0, 0),
    AFTER_PREPARE(0, 0),
    AFTER_FIRST_CREDENTIAL(1, 0),
    AFTER_FIRST_ROW(1, 1),
    AFTER_SECOND_CREDENTIAL(2, 1),
    AFTER_SECOND_ROW(2, 2),
    BEFORE_GROUP_WRITE(2, 2),
    AFTER_GROUP_WRITE(2, 2, persistsGroup = true),
    BEFORE_COMPLETE(2, 2, persistsGroup = true, publishesGroup = true),
    AFTER_COMPLETE(2, 2, persistsGroup = true, publishesGroup = true, completed = true),
}

private class SubscriptionRefreshFault(
    val cut: SubscriptionRefreshFaultCut,
    cancellation: Boolean,
) {
    val failure =
        if (cancellation) {
            CancellationException(
                "Interrupted at $cut",
            )
        } else {
            IllegalStateException("Failed at $cut")
        }
    var fired = false
        private set
    var pending: PendingProfileMutation? = null
        private set
    val intent: SubscriptionRefreshIntent?
        get() =
            pending?.let { RipDpiContractJson.decodeFromString(ProfileMutationIntent.serializer(), it.payload) }
                as SubscriptionRefreshIntent?

    fun install(fixture: ProfileMutationTestFixture) {
        fixture.beforePrepare = { failAt(SubscriptionRefreshFaultCut.BEFORE_PREPARE) }
        fixture.afterPrepare = {
            pending = it
            failAt(SubscriptionRefreshFaultCut.AFTER_PREPARE)
        }
        fixture.awgCredentials.afterSave = {
            when (fixture.awgCredentials.saveAttempts) {
                1 -> failAt(SubscriptionRefreshFaultCut.AFTER_FIRST_CREDENTIAL)
                2 -> failAt(SubscriptionRefreshFaultCut.AFTER_SECOND_CREDENTIAL)
            }
        }
        fixture.awgProfiles.afterUpsert = {
            when (fixture.awgProfiles.upsertAttempts) {
                1 -> failAt(SubscriptionRefreshFaultCut.AFTER_FIRST_ROW)
                2 -> failAt(SubscriptionRefreshFaultCut.AFTER_SECOND_ROW)
            }
        }
        fixture.beforeGroupWrite = { failAt(SubscriptionRefreshFaultCut.BEFORE_GROUP_WRITE) }
        fixture.afterGroupWrite = { failAt(SubscriptionRefreshFaultCut.AFTER_GROUP_WRITE) }
        fixture.journal.beforeComplete = { failAt(SubscriptionRefreshFaultCut.BEFORE_COMPLETE) }
        fixture.journal.afterComplete = { failAt(SubscriptionRefreshFaultCut.AFTER_COMPLETE) }
    }

    fun clear(fixture: ProfileMutationTestFixture) {
        fixture.beforePrepare = null
        fixture.afterPrepare = null
        fixture.awgCredentials.afterSave = null
        fixture.awgProfiles.afterUpsert = null
        fixture.beforeGroupWrite = null
        fixture.afterGroupWrite = null
        fixture.journal.beforeComplete = null
        fixture.journal.afterComplete = null
    }

    fun expectedObserved(
        before: List<ProxyGroup>,
        cold: Boolean,
        recovered: Boolean,
    ): List<List<ProxyGroup>> =
        buildList {
            if (!cold) add(before)
            val replayed = recovered && !cut.completed
            val alreadyPublished = !cold && cut.publishesGroup
            if (replayed || alreadyPublished) intent?.let { add(it.groups) }
        }

    private fun failAt(boundary: SubscriptionRefreshFaultCut) {
        if (boundary == cut) {
            fired = true
            throw failure
        }
    }
}

private data class SubscriptionPersistedState(
    val groups: List<ProxyGroup>,
    val rows: List<AwgProfileEntity>,
    val secrets: Map<String, AwgSecrets?>,
)

private data class SubscriptionRecoveryOwner(
    val mutations: ProfileMutationRecoveryCoordinator,
    val groups: SharedPreferencesProxyGroupRepository,
    val authority: PauseIntentAuthority,
)

private class SubscriptionFaultScenario {
    val fixture = ProfileMutationTestFixture()
    val mutations = fixture.coordinator()
    val groups = SharedPreferencesProxyGroupRepository(fixture.groupCatalog, mutations)
    private val awg = AwgProfileRepository(fixture.awgProfiles, fixture.awgCredentials, mutations)
    lateinit var expected: ProxyGroup
        private set
    lateinit var before: SubscriptionPersistedState
        private set
    private var seededProfiles = emptyList<SavedAwgProfile>()
    private var preparedProfiles = emptyList<SavedAwgProfile>()
    var preparations = 0
        private set

    suspend fun seed() {
        groups.add(
            ProxyGroup(
                "subscription",
                "Subscription",
                ProxyGroupType.SUBSCRIPTION,
                0,
                false,
                subscription = Subscription(link = "https://subscription.example/config"),
            ),
        )
        groups.add(ProxyGroup("unrelated", "Unrelated", ProxyGroupType.BASIC, 1, false))
        expected = groups.list().first()
        assertTrue(
            awg.commitSubscriptionRefresh(expected, profiles(rotated = false)) {
                seededProfiles = it
                expected.copy(subscription = checkNotNull(expected.subscription).copy(lastUpdated = 1_000L))
            },
        )
        expected = groups.list().first()
        before = snapshot()
        assertEquals(2, seededProfiles.size)
        assertEquals(2, seededProfiles.map { it.id }.distinct().size)
        seededProfiles.forEach { it.request.requireRuntimeReady() }
        fixture.prepareAttempts = 0
        fixture.awgCredentials.saveAttempts = 0
        fixture.awgProfiles.upsertAttempts = 0
        fixture.groupWriteAttempts = 0
    }

    suspend fun refresh(): Boolean =
        awg.commitSubscriptionRefresh(expected, profiles(rotated = true)) {
            preparations++
            preparedProfiles = it
            expected.copy(
                subscription = checkNotNull(expected.subscription).copy(lastUpdated = 2_000L, bytesUsed = 8_192L),
            )
        }

    fun recovery(cold: Boolean): SubscriptionRecoveryOwner {
        if (!cold) return SubscriptionRecoveryOwner(mutations, groups, fixture.pauseAuthority)
        val catalog = ProxyGroupCatalog(fixture.groupBlob)
        val authority = testPauseAuthority(fixture.persistence, initialize = false)
        val restarted = fixture.coordinator(catalog = catalog, authority = authority)
        assertEquals(
            checkNotNull(fixture.pauseAuthority.states.value).profileUtility.catalogReady,
            checkNotNull(authority.states.value).profileUtility.catalogReady,
        )
        return SubscriptionRecoveryOwner(
            restarted,
            SharedPreferencesProxyGroupRepository(catalog, restarted),
            authority,
        )
    }

    suspend fun snapshot(): SubscriptionPersistedState {
        val rows = fixture.awgProfiles.allProfiles().sortedBy { it.id }
        return SubscriptionPersistedState(
            ProxyGroupCatalog(fixture.groupBlob).snapshot(),
            rows,
            rows.associate { it.id to fixture.awgCredentials.load(it.id) },
        )
    }

    fun writeCounts(): List<Int> =
        listOf(
            fixture.prepareAttempts,
            fixture.awgCredentials.saveAttempts,
            fixture.awgProfiles.upsertAttempts,
            fixture.groupWriteAttempts,
        )

    suspend fun assertInterrupted(fault: SubscriptionRefreshFault) {
        assertEquals(1, preparations)
        assertEquals(1, fixture.prepareAttempts)
        assertEquals(if (fault.cut.completed) null else fault.pending, fixture.journal.pending())
        assertEquals(fault.cut.credentialsWritten, fixture.awgCredentials.saveAttempts)
        assertEquals(fault.cut.rowsWritten, fixture.awgProfiles.upsertAttempts)
        assertEquals(
            fault.cut.publishesGroup,
            checkNotNull(fixture.pauseAuthority.states.value).profileUtility.catalogReady,
        )
        assertPreparedProfiles()
        val intent = fault.intent
        if (intent == null) {
            assertEquals(SubscriptionRefreshFaultCut.BEFORE_PREPARE, fault.cut)
            assertEquals(before, snapshot())
            assertEquals(0, fixture.groupWriteAttempts)
        } else {
            val rows = intent.awgProfiles.take(fault.cut.rowsWritten).associate { it.id to it.toEntity() }
            val secrets = intent.awgProfiles.take(fault.cut.credentialsWritten).associate { it.id to it.secrets }
            assertEquals(
                SubscriptionPersistedState(
                    if (fault.cut.persistsGroup) intent.groups else before.groups,
                    before.rows.map { rows[it.id] ?: it },
                    before.secrets.mapValues { (id, previous) -> secrets[id] ?: previous },
                ),
                snapshot(),
            )
            assertEquals(before.groups.last(), intent.groups.last())
            val accepted = intent.groups.first()
            assertNotEquals(expected.ownershipGeneration, accepted.ownershipGeneration)
            assertEquals(2_000L, accepted.subscription?.lastUpdated)
        }
    }

    suspend fun assertRecovered(
        fault: SubscriptionRefreshFault,
        recovery: SubscriptionRecoveryOwner,
    ) {
        val intent = fault.intent
        val expectedState =
            if (intent == null) {
                before
            } else {
                SubscriptionPersistedState(
                    intent.groups,
                    intent.awgProfiles.map { it.toEntity() }.sortedBy { it.id },
                    intent.awgProfiles.associate { it.id to it.secrets },
                )
            }
        assertEquals(expectedState, snapshot())
        assertNull(fixture.journal.pending())
        assertEquals(1, preparations)
        assertEquals(1, fixture.prepareAttempts)
        assertTrue(checkNotNull(recovery.authority.states.value).profileUtility.catalogReady)
        val repository = AwgProfileRepository(fixture.awgProfiles, fixture.awgCredentials, recovery.mutations)
        val actual = seededProfiles.map { checkNotNull(repository.load(it.id)) }
        assertEquals(if (intent == null) seededProfiles else preparedProfiles, actual)
        actual.forEach { it.request.requireRuntimeReady() }
    }

    private fun assertPreparedProfiles() {
        assertEquals(seededProfiles.map { it.id }, preparedProfiles.map { it.id })
        preparedProfiles.forEachIndexed { index, profile ->
            assertEquals(seededProfiles[index].request.privateKey, profile.request.privateKey)
            assertEquals("192.0.2.${20 + index}", profile.request.endpointHost)
            assertEquals(8443, profile.request.endpointPort)
            assertEquals(subscriptionTestKey(40 + index), profile.request.presharedKey)
            profile.request.requireRuntimeReady()
        }
    }

    private fun profiles(rotated: Boolean): List<AwgSubscriptionProfile> =
        (0..1).map { index ->
            AwgSubscriptionProfile(
                groupId = "subscription",
                memberId = "member-$index",
                name = "Member $index",
                request =
                    AwgActivationRequest(
                        profileId = "",
                        privateKey = if (rotated) "" else subscriptionTestKey(index + 1),
                        peerPublicKey = subscriptionTestKey(20 + index),
                        presharedKey = subscriptionTestKey((if (rotated) 40 else 30) + index),
                        endpointHost = "192.0.2.${(if (rotated) 20 else 10) + index}",
                        endpointPort = if (rotated) 8443 else 443,
                        interfaceAddressV4 = "10.8.0.${index + 2}/32",
                    ),
            )
        }
}

private fun subscriptionTestKey(value: Int): String =
    Base64.getEncoder().encodeToString(
        ByteArray(32) {
            value.toByte()
        },
    )
