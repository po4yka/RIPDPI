package com.poyka.ripdpi.shortcuts

import android.app.Application
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.selector.SelectorSelectionSnapshot
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
            val capability = SelectorShortcutCapability(application)

            val publisher =
                AppShortcutsPublisher(
                    context = application,
                    proxyGroupRepository = FlowBackedProxyGroupRepository(groups),
                    selectorSelectionStore = selectorStore,
                    selectorShortcutCapability = capability,
                    applicationScope = backgroundScope,
                )

            publisher.start()
            advanceUntilIdle()

            val dynamic = ShortcutManagerCompat.getDynamicShortcuts(application)
            assertTrue("expected at most 4 dynamic shortcuts, got ${dynamic.size}", dynamic.size <= 4)
            assertTrue(dynamic.all { shortcut -> capability.verifies(shortcut.intent) })
        }

    @Test
    fun `start is idempotent across repeated calls`() =
        runTest {
            val publisher =
                AppShortcutsPublisher(
                    context = application,
                    proxyGroupRepository = FlowBackedProxyGroupRepository(MutableStateFlow(emptyList())),
                    selectorSelectionStore = FakeSelectorSelectionStore(),
                    selectorShortcutCapability = SelectorShortcutCapability(application),
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
                    selectorShortcutCapability = SelectorShortcutCapability(application),
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
                    selectorShortcutCapability = SelectorShortcutCapability(application),
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
    private val manual = mutableSetOf<String>()
    private val revisions = mutableMapOf<String, Long>()

    fun set(
        groupId: String,
        profileId: String?,
    ) = write(groupId, profileId, false)

    override fun selectedProfileId(groupId: String): StateFlow<String?> = flowFor(groupId).asStateFlow()

    override fun invalidatePendingSelection(groupId: String) {
        revisions[groupId] = (revisions[groupId] ?: 0L) + 1L
    }

    override fun snapshot(groupId: String): SelectorSelectionSnapshot =
        SelectorSelectionSnapshot(flowFor(groupId).value, groupId in manual, revisions[groupId] ?: 0L)

    override fun selectAutomatically(
        groupId: String,
        expected: SelectorSelectionSnapshot,
        profileId: String,
    ): Boolean {
        if (snapshot(groupId) != expected) return false
        write(groupId, profileId, false)
        return true
    }

    override fun select(
        groupId: String,
        profileId: String,
    ) = write(groupId, profileId, true)

    override fun clearSelection(groupId: String) = write(groupId, null, false)

    private fun write(
        groupId: String,
        profileId: String?,
        isManual: Boolean,
    ) {
        if (isManual) manual.add(groupId) else manual.remove(groupId)
        revisions[groupId] = (revisions[groupId] ?: 0L) + 1L
        flowFor(groupId).value = profileId
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
