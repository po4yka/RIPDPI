package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgSecrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRefreshFaultMatrixTest : RoomProfileTest() {
    @Test
    fun `every storage cut rolls back both AWG pairs group ownership and unrelated state`() =
        runTest {
            for (cut in RefreshStorageCut.entries) {
                val scenario = RefreshScenario(fixture())
                scenario.seed()
                val before = scenario.fixture.snapshot()
                cut.install(scenario.fixture)
                assertTrue("$cut must fail", runCatching { scenario.refresh() }.isFailure)
                assertEquals("$cut must preserve every old row", before, scenario.fixture.snapshot())
                cut.clear(scenario.fixture)
                scenario.fixture.coordinator().recover()
                assertEquals(before, scenario.fixture.snapshot())
                scenario.fixture.reopen()
                assertEquals("$cut must not replay after reopen", before, scenario.fixture.snapshot())
                assertTrue(scenario.refresh())
                scenario.assertAccepted()
                val accepted = scenario.fixture.snapshot()
                scenario.fixture.reopen()
                scenario.assertAccepted()
                assertEquals(accepted, scenario.fixture.snapshot())
            }
        }

    @Test
    fun `cancellation before each credential or catalogue write retains the complete old snapshot`() =
        runTest {
            for (target in listOf(
                "awg_credentials" to "one",
                "awg_credentials" to "two",
                "proxy_groups" to "current",
            )) {
                val scenario = RefreshScenario(fixture())
                scenario.seed()
                val before = scenario.fixture.snapshot()
                val barrier = ProfileWriteBarrier(target.first)
                scenario.fixture.room.beforeSeal = { namespace, id ->
                    if (id == target.second) barrier.beforeSeal(namespace, id)
                }
                val operation = launch { scenario.refresh() }
                try {
                    barrier.awaitEntry()
                    operation.cancel()
                } finally {
                    barrier.release()
                }
                operation.join()
                scenario.fixture.clearFaults()
                assertEquals(before, scenario.fixture.snapshot())
                scenario.fixture.reopen()
                assertEquals(before, scenario.fixture.snapshot())
                assertTrue(scenario.refresh())
                scenario.assertAccepted()
            }
        }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SubscriptionRefreshRecoveryEditTest : RoomProfileTest() {
    @Test
    fun `competing edit waits for rollback then survives every later read and reopen`() =
        runTest {
            val scenario = RefreshScenario(fixture())
            scenario.seed()
            val barrier = ProfileWriteBarrier("proxy_groups")
            scenario.fixture.room.beforeSeal = { namespace, id ->
                if (namespace == "proxy_groups") {
                    barrier.beforeSeal(namespace, id)
                    scenario.fixture.clearFaults()
                    error("Refresh group write failed")
                }
            }
            val refresh = async { runCatching { scenario.refresh() } }
            try {
                barrier.awaitEntry()
                val edit =
                    async {
                        scenario.fixture.groups.updateGroup(
                            "subscription",
                        ) { it.copy(name = "Edited after rollback") }
                    }
                runCurrent()
                assertFalse(edit.isCompleted)
                barrier.release()
                assertTrue(refresh.await().isFailure)
                assertEquals("Edited after rollback", edit.await()?.name)
            } finally {
                barrier.release()
            }
            assertEquals(AwgSecrets("old-private", "old-psk"), scenario.fixture.awgCredentials.load("one"))
            assertNull(scenario.fixture.awgCredentials.load("two"))
            val final = scenario.fixture.snapshot()
            scenario.fixture.coordinator().recover()
            assertEquals(final, scenario.fixture.snapshot())
            scenario.fixture.reopen()
            assertEquals(final, scenario.fixture.snapshot())
            assertEquals(
                "Edited after rollback",
                scenario.fixture.groups
                    .list()
                    .first { it.id == "subscription" }
                    .name,
            )
        }
}

private enum class RefreshStorageCut {
    BEFORE_FIRST_SECRET,
    AFTER_FIRST_SECRET,
    BEFORE_SECOND_SECRET,
    AFTER_SECOND_SECRET,
    BEFORE_GROUP,
    AFTER_GROUP,
    ;

    suspend fun install(fixture: ProfileMutationTestFixture) {
        when (this) {
            BEFORE_FIRST_SECRET, BEFORE_SECOND_SECRET, BEFORE_GROUP -> {
                fixture.room.beforeSeal = { namespace, id ->
                    val target =
                        when (this) {
                            BEFORE_FIRST_SECRET -> "awg_credentials" to "one"
                            BEFORE_SECOND_SECRET -> "awg_credentials" to "two"
                            else -> "proxy_groups" to "current"
                        }
                    if ((namespace to id) == target) error("Injected refresh cut $this")
                }
            }

            else -> {
                withContext(Dispatchers.IO) {
                    val sql =
                        when (this@RefreshStorageCut) {
                            AFTER_FIRST_SECRET -> "BEFORE INSERT ON awg_profiles WHEN NEW.id = 'one'"
                            AFTER_SECOND_SECRET -> "BEFORE INSERT ON awg_profiles WHEN NEW.id = 'two'"
                            else -> "AFTER UPDATE ON profile_records WHEN NEW.namespace = 'proxy_groups'"
                        }
                    fixture.room.database.openHelper.writableDatabase.execSQL(
                        "CREATE TRIGGER refresh_failure $sql BEGIN SELECT RAISE(ABORT, 'refresh cut'); END",
                    )
                }
            }
        }
    }

    suspend fun clear(fixture: ProfileMutationTestFixture) {
        fixture.clearFaults()
        withContext(Dispatchers.IO) {
            fixture.room.database.openHelper.writableDatabase
                .execSQL("DROP TRIGGER IF EXISTS refresh_failure")
        }
    }
}

private class RefreshScenario(
    val fixture: ProfileMutationTestFixture,
) {
    private lateinit var original: ProxyGroup
    private val one = AwgProfileEntity("one", "Rotated one", "{\"next\":1}", 20L)
    private val two = AwgProfileEntity("two", "New two", "{\"next\":2}", 21L)
    private val unrelated = AwgProfileEntity("unrelated", "Unrelated", "{}", 5L)

    suspend fun seed() {
        fixture.initialize()
        fixture.groups.add(
            ProxyGroup(
                "subscription",
                "Subscription",
                ProxyGroupType.SUBSCRIPTION,
                0,
                false,
                subscription = Subscription(link = "https://subscription.example/config"),
            ),
        )
        fixture.groups.add(ProxyGroup("unrelated", "Other", ProxyGroupType.BASIC, 1, false))
        original = fixture.groups.list().first { it.id == "subscription" }
        fixture.settings.update { setProxyPort(18080) }
        fixture.coordinator().upsertAwg(
            fixture.coordinator().captureMutation(ProfileMutationOrigin.SavedEdit),
            one.copy(name = "Old one", requestJson = "{}", updatedAt = 10L),
            AwgSecrets("old-private", "old-psk"),
        )
        fixture.coordinator().upsertAwg(
            fixture.coordinator().captureMutation(ProfileMutationOrigin.SavedEdit),
            unrelated,
            AwgSecrets("unrelated-private", "unrelated-psk"),
        )
    }

    suspend fun refresh(): Boolean =
        fixture.coordinator().commitSubscriptionRefresh(original) {
            PreparedSubscriptionRefresh(
                original.copy(subscription = original.subscription!!.copy(lastUpdated = 3000L)),
                listOf(
                    SubscriptionAwgWrite(one, AwgSecrets("one-private", "one-psk")),
                    SubscriptionAwgWrite(two, AwgSecrets("two-private", "two-psk")),
                ),
            )
        }

    suspend fun assertAccepted() {
        val groups = fixture.groups.list()
        val accepted = groups.first { it.id == "subscription" }
        assertEquals(3000L, accepted.subscription?.lastUpdated)
        assertNotEquals(original.ownershipGeneration, accepted.ownershipGeneration)
        assertEquals("Other", groups.first { it.id == "unrelated" }.name)
        assertEquals(18080, fixture.settings.snapshot().proxyPort)
        assertEquals(AwgSecrets("one-private", "one-psk"), fixture.awgCredentials.load("one"))
        assertEquals(AwgSecrets("two-private", "two-psk"), fixture.awgCredentials.load("two"))
        assertEquals(AwgSecrets("unrelated-private", "unrelated-psk"), fixture.awgCredentials.load("unrelated"))
        withContext(Dispatchers.IO) {
            assertEquals(one, fixture.awgProfiles.getProfile("one"))
            assertEquals(two, fixture.awgProfiles.getProfile("two"))
            assertEquals(unrelated, fixture.awgProfiles.getProfile("unrelated"))
        }
    }
}
