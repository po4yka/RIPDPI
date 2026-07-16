package com.poyka.ripdpi.data

import app.cash.turbine.test
import com.poyka.ripdpi.data.routing.PackageRoutingAction
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.data.routing.PackageRoutingRuleOrigin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProxyGroupRepositoryTest {
    private fun newRepository(): SharedPreferencesProxyGroupRepository =
        SharedPreferencesProxyGroupRepository(FakeProxyGroupBlobStore())

    private fun group(
        id: String,
        order: Int = 0,
    ) = ProxyGroup(
        id = id,
        name = "Group $id",
        type = ProxyGroupType.BASIC,
        order = order,
        isSelector = false,
    )

    @Test
    fun `add then list returns inserted groups ordered by order`() =
        runTest {
            val repository = newRepository()

            repository.add(group("b", order = 2))
            repository.add(group("a", order = 1))

            assertEquals(listOf("a", "b"), repository.list().map(ProxyGroup::id))
        }

    @Test
    fun `update replaces an existing group`() =
        runTest {
            val repository = newRepository()
            repository.add(group("g1"))

            repository.update(group("g1").copy(name = "renamed"))

            assertEquals("renamed", repository.list().single().name)
        }

    @Test
    fun `atomic subscription update preserves concurrent group fields`() =
        runTest {
            val repository = newRepository()
            repository.add(
                group("g1").copy(
                    type = ProxyGroupType.SUBSCRIPTION,
                    subscription = Subscription(autoUpdate = true),
                ),
            )
            repository.update(
                group(
                    "g1",
                ).copy(
                    name = "renamed",
                    type = ProxyGroupType.SUBSCRIPTION,
                    subscription = Subscription(autoUpdate = true),
                ),
            )

            repository.updateSubscription("g1") {
                it.copy(lastRefreshFailure = SubscriptionRefreshFailure.NETWORK_ERROR)
            }

            val stored = repository.list().single()
            assertEquals("renamed", stored.name)
            assertEquals(SubscriptionRefreshFailure.NETWORK_ERROR, stored.subscription?.lastRefreshFailure)
        }

    @Test
    fun `delete removes a group`() =
        runTest {
            val repository = newRepository()
            repository.add(group("g1"))
            repository.add(group("g2"))

            repository.delete("g1")

            assertEquals(listOf("g2"), repository.list().map(ProxyGroup::id))
            assertNull(repository.list().firstOrNull { it.id == "g1" })
        }

    @Test
    fun `package rules survive reload and disappear with their group`() =
        runTest {
            val blobStore = FakeProxyGroupBlobStore()
            val first = SharedPreferencesProxyGroupRepository(blobStore)
            val rule =
                PackageRoutingRule(
                    packageName = "com.persisted.app",
                    action = PackageRoutingAction.VIA_TUN,
                    origin = PackageRoutingRuleOrigin.Subscription("g1"),
                )
            first.add(group("g1").copy(packageRoutingRules = listOf(rule)))

            val reloaded = SharedPreferencesProxyGroupRepository(blobStore)
            assertEquals(listOf(rule), reloaded.list().single().packageRoutingRules)

            reloaded.delete("g1")
            val afterDelete = SharedPreferencesProxyGroupRepository(blobStore)
            assertTrue(afterDelete.list().isEmpty())
        }

    @Test
    fun `groups flow emits on every change`() =
        runTest {
            val repository = newRepository()

            repository.groups().test {
                assertEquals(emptyList<ProxyGroup>(), awaitItem())

                repository.add(group("g1"))
                assertEquals(listOf("g1"), awaitItem().map(ProxyGroup::id))

                repository.update(group("g1").copy(name = "renamed"))
                assertEquals("renamed", awaitItem().single().name)

                repository.delete("g1")
                assertEquals(emptyList<ProxyGroup>(), awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }
}

/** In-memory [ProxyGroupBlobStore]; AndroidKeyStore is unavailable under Robolectric. */
private class FakeProxyGroupBlobStore : ProxyGroupBlobStore {
    private var blob: String? = null

    override fun read(): String? = blob

    override fun write(json: String) {
        blob = json
    }

    override fun clear() {
        blob = null
    }
}
