package com.poyka.ripdpi.shortcuts

import android.app.Application
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.selector.SelectorSelectionStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AppShortcutsPublisherTest {
    private val application: Application by lazy { ApplicationProvider.getApplicationContext() }

    @Test
    fun `dynamic shortcuts cap at four entries`() =
        runTest {
            val groups =
                MutableStateFlow(
                    (0 until 6).map { idx ->
                        proxyGroup(id = "group-$idx", name = "Group $idx", selector = true)
                    },
                )
            val selectorStore = FakeSelectorSelectionStore()
            repeat(6) { selectorStore.set("group-$it", "profile-$it") }

            val publisher =
                AppShortcutsPublisher(
                    context = application,
                    proxyGroupRepository = FlowBackedProxyGroupRepository(groups),
                    selectorSelectionStore = selectorStore,
                    applicationScope = backgroundScope,
                )

            publisher.start()
            advanceUntilIdle()

            val dynamic = ShortcutManagerCompat.getDynamicShortcuts(application)
            assertTrue("expected at most 4 dynamic shortcuts, got ${dynamic.size}", dynamic.size <= 4)
        }

    @Test
    fun `start is idempotent across repeated calls`() =
        runTest {
            val publisher =
                AppShortcutsPublisher(
                    context = application,
                    proxyGroupRepository = FlowBackedProxyGroupRepository(MutableStateFlow(emptyList())),
                    selectorSelectionStore = FakeSelectorSelectionStore(),
                    applicationScope = backgroundScope,
                )

            publisher.start()
            publisher.start()
            advanceUntilIdle()
            // No assertion: a second start() must not crash; the AtomicBoolean guard prevents
            // duplicate flow collectors. Reaching this point without an exception is the contract.
        }

    @Test
    fun `non-selector groups are ignored`() =
        runTest {
            val groups =
                MutableStateFlow(
                    listOf(
                        proxyGroup(id = "g1", name = "Standalone", selector = false),
                    ),
                )

            val publisher =
                AppShortcutsPublisher(
                    context = application,
                    proxyGroupRepository = FlowBackedProxyGroupRepository(groups),
                    selectorSelectionStore = FakeSelectorSelectionStore(),
                    applicationScope = backgroundScope,
                )

            publisher.start()
            advanceUntilIdle()

            assertTrue(ShortcutManagerCompat.getDynamicShortcuts(application).isEmpty())
        }

    @Test
    fun `selector group without a selection is not surfaced`() =
        runTest {
            val groups =
                MutableStateFlow(
                    listOf(proxyGroup(id = "group-x", name = "Group X", selector = true)),
                )
            // selectorStore has no recorded selection for group-x

            val publisher =
                AppShortcutsPublisher(
                    context = application,
                    proxyGroupRepository = FlowBackedProxyGroupRepository(groups),
                    selectorSelectionStore = FakeSelectorSelectionStore(),
                    applicationScope = backgroundScope,
                )

            publisher.start()
            advanceUntilIdle()

            assertTrue(ShortcutManagerCompat.getDynamicShortcuts(application).isEmpty())
        }

    private fun proxyGroup(
        id: String,
        name: String,
        selector: Boolean,
    ): ProxyGroup =
        ProxyGroup(
            id = id,
            name = name,
            type = ProxyGroupType.BASIC,
            order = 0,
            isSelector = selector,
            subscription = null,
        )
}

private class FakeSelectorSelectionStore : SelectorSelectionStore {
    private val flows = HashMap<String, MutableStateFlow<String?>>()

    fun set(
        groupId: String,
        profileId: String?,
    ) {
        flowFor(groupId).value = profileId
    }

    override fun selectedProfileId(groupId: String): StateFlow<String?> = flowFor(groupId).asStateFlow()

    override fun select(
        groupId: String,
        profileId: String,
    ) {
        flowFor(groupId).value = profileId
    }

    override fun clearSelection(groupId: String) {
        flowFor(groupId).value = null
    }

    private fun flowFor(groupId: String): MutableStateFlow<String?> = flows.getOrPut(groupId) { MutableStateFlow(null) }
}

private class FlowBackedProxyGroupRepository(
    private val source: MutableStateFlow<List<ProxyGroup>>,
) : ProxyGroupRepository {
    override suspend fun add(group: ProxyGroup) {
        source.value = source.value + group
    }

    override suspend fun update(group: ProxyGroup) {
        source.value = source.value.map { if (it.id == group.id) group else it }
    }

    override suspend fun delete(id: String) {
        source.value = source.value.filterNot { it.id == id }
    }

    override suspend fun list(): List<ProxyGroup> = source.value

    override fun groups(): Flow<List<ProxyGroup>> = source.asStateFlow()
}
