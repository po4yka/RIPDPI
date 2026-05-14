package com.poyka.ripdpi.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ProxyGroupRepositoryTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun newRepository(): SharedPreferencesProxyGroupRepository {
        val repository = SharedPreferencesProxyGroupRepository(context)
        repository.clearAll()
        return repository
    }

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
