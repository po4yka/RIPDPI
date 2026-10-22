package com.poyka.ripdpi.proxyimport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupBlobStore
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.SharedPreferencesProxyGroupRepository
import com.poyka.ripdpi.data.selector.SelectorActiveGroupStore
import com.poyka.ripdpi.data.selector.SharedPreferencesSelectorSelectionStore
import com.poyka.ripdpi.data.testMutationPreparationSource
import com.poyka.ripdpi.data.testPauseAuthority
import com.poyka.ripdpi.serialization.RipDpiJson
import com.poyka.ripdpi.services.RuntimePolicyReloadIntent
import com.poyka.ripdpi.services.selector.SelectorReloadCoordinator
import com.poyka.ripdpi.testsupport.FakeServiceStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises lifecycle preparation with the production deferred repository, without app startup. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SelectorColdStartupTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearSelection() {
        check(
            context
                .getSharedPreferences("selector_selection_store", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit(),
        )
    }

    @Test
    fun `empty cold repository seeds lifecycle without app initialization`() =
        runTest {
            val fixture = Fixture(backgroundScope, StoredGroups(null))
            fixture.listener.start(Mode.Proxy)
            runCurrent()

            withTimeout(1_000L) { fixture.listener.prepare() }
            fixture.listener.afterStart()

            assertEquals(emptyList<ProxyGroup>(), fixture.groups.groups().first())
            assertNull(fixture.active.activeGroupId.value)
            assertEquals(0, fixture.refresher.refreshes)
            assertEquals(0, fixture.store.writes)
        }

    @Test
    fun `persisted selector is loaded before lifecycle snapshots its choice`() =
        runTest {
            val member =
                ProxyProfile.Trojan(
                    "member",
                    "Member",
                    "reserve",
                    "relay.example.test",
                    443,
                    "fixture-password",
                )
            val group = ProxyGroup("reserve", "Reserve", ProxyGroupType.SUBSCRIPTION, 0, true, members = listOf(member))
            val stored = RipDpiJson.encodeToString(ListSerializer(ProxyGroup.serializer()), listOf(group))
            check(
                context
                    .getSharedPreferences("selector_selection_store", Context.MODE_PRIVATE)
                    .edit()
                    .putString("selected-profile-reserve", "member")
                    .commit(),
            )
            val fixture = Fixture(backgroundScope, StoredGroups(stored))
            fixture.listener.start(Mode.VPN)

            withTimeout(1_000L) { fixture.listener.prepare() }
            fixture.listener.afterStart()

            assertEquals("reserve", fixture.active.activeGroupId.value)
            assertEquals("member", fixture.selections.snapshot("reserve").profileId)
            assertEquals(listOf(group), fixture.groups.groups().first())
            assertEquals(stored, fixture.store.value)
            assertEquals(0, fixture.store.writes)
            assertEquals(0, fixture.refresher.refreshes)
        }

    @Test
    fun `unreadable cold repository rejects preparation and can retry after recovery`() =
        runTest {
            val failure = IllegalStateException("Unreadable persisted catalogue")
            val store = StoredGroups("unreadable").apply { readFailure = failure }
            val fixture = Fixture(backgroundScope, store)
            val snapshots = mutableListOf<List<ProxyGroup>>()
            backgroundScope.launch { fixture.groups.groups().collect { snapshots += it } }
            fixture.listener.start(Mode.VPN)
            runCurrent()

            val observed =
                withTimeout(1_000L) {
                    // Catch before coroutine stack-trace recovery can copy the original exception.
                    runCatching { fixture.listener.prepare() }.exceptionOrNull()
                }

            assertSame(failure, observed)
            assertTrue(snapshots.isEmpty())
            assertEquals("unreadable", store.value)
            assertEquals(0, store.writes)
            assertEquals(0, fixture.refresher.refreshes)
            store.readFailure = null
            store.value = null
            withTimeout(1_000L) { fixture.listener.prepare() }
            assertEquals(emptyList<ProxyGroup>(), fixture.groups.groups().first())
        }

    private class Fixture(
        scope: CoroutineScope,
        val store: StoredGroups,
    ) {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val authority = testPauseAuthority()
        private val preparation = testMutationPreparationSource()
        val groups =
            SharedPreferencesProxyGroupRepository(
                com.poyka.ripdpi.data
                    .ProxyGroupCatalog(store),
                preparation,
            )
        val active = SelectorActiveGroupStore(context, authority)
        val selections = SharedPreferencesSelectorSelectionStore(context, preparation, authority, active)
        private val provider = ActiveSelectorSelectionProvider(groups, selections, active)
        val refresher = RecordingRefresher()
        private val trigger =
            RelayActivationSelectorReloadTrigger(
                groups,
                selections,
                authority,
                FakeServiceStateStore(),
                refresher,
                provider,
            )
        private val coordinator = SelectorReloadCoordinator(scope, provider.selectionChanges(), trigger)
        val listener = SelectorReloadModule.provideSelectorReloadLifecycleListener(coordinator, trigger, groups)
    }

    private class StoredGroups(
        var value: String?,
    ) : ProxyGroupBlobStore {
        var readFailure: Exception? = null
        var writes = 0

        override fun read(): String? {
            readFailure?.let { throw it }
            return value
        }

        override fun write(json: String) {
            writes += 1
            value = json
        }

        override fun clear() {
            writes += 1
            value = null
        }
    }

    private class RecordingRefresher : RunningRelayRefresher {
        var refreshes = 0

        override suspend fun refresh(
            intent: RuntimePolicyReloadIntent,
            isCurrent: suspend () -> Boolean,
        ) {
            refreshes += 1
        }

        override suspend fun teardown() = Unit
    }
}
