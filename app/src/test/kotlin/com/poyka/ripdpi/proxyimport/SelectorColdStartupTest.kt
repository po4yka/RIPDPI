package com.poyka.ripdpi.proxyimport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.testPauseAuthority
import com.poyka.ripdpi.data.testing.RoomProfileFixture
import com.poyka.ripdpi.services.RuntimePolicyReloadIntent
import com.poyka.ripdpi.services.selector.SelectorReloadCoordinator
import com.poyka.ripdpi.testsupport.FakeServiceStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Lifecycle preparation reads canonical Room state without the app startup initializer. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SelectorColdStartupTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databases = mutableListOf<RoomProfileFixture>()

    @After
    fun closeStorage() = databases.forEach { it.close() }

    @Test
    fun `empty cold repository seeds lifecycle without app initialization`() =
        runTest {
            val persistence = storage()
            val fixture = Fixture(backgroundScope, persistence)
            fixture.listener.start(Mode.Proxy)
            runCurrent()
            withContext(Dispatchers.Default) { withTimeout(5_000L) { fixture.listener.prepare() } }
            fixture.listener.afterStart()
            assertEquals(emptyList<ProxyGroup>(), persistence.groups.groups().first())
            assertNull(persistence.selectorActiveGroup.snapshotActiveGroupId())
            assertEquals(0, fixture.refresher.refreshes)
        }

    @Test
    fun `persisted selector is loaded before lifecycle snapshots its choice`() =
        runTest {
            val initial = storage()
            val group = savedGroup()
            initial.groups.add(group)
            val stored = initial.groups.list().single()
            val choice = initial.selectorSelections.snapshot(group.id)
            assertTrue(initial.selectorSelections.selectAutomatically(group.id, choice, "member"))
            initial.selectorActiveGroup.restoreActiveGroup(group.id)
            val persistence = initial.reopen(testPauseAuthority()).also { databases += it }
            persistence.initialize()
            var writes = 0
            persistence.beforeSeal = { _, _ -> writes++ }
            val fixture = Fixture(backgroundScope, persistence)
            fixture.listener.start(Mode.VPN)
            withContext(Dispatchers.Default) { withTimeout(5_000L) { fixture.listener.prepare() } }
            fixture.listener.afterStart()
            assertEquals("reserve", persistence.selectorActiveGroup.snapshotActiveGroupId())
            assertEquals("member", persistence.selectorSelections.snapshot("reserve").profileId)
            assertEquals(listOf(stored), persistence.groups.groups().first())
            assertEquals(0, writes)
            assertEquals(0, fixture.refresher.refreshes)
        }

    @Test
    fun `unreadable cold repository rejects preparation and explicit retry restores observation`() =
        runTest {
            val persistence = storage()
            persistence.groups.add(savedGroup())
            val stored = persistence.groups.list()
            val failure = IllegalStateException("Unreadable persisted catalogue")
            persistence.beforeOpen = { namespace, _ -> if (namespace == "proxy_groups") throw failure }
            val fixture = Fixture(backgroundScope, persistence)
            fixture.listener.start(Mode.VPN)
            runCurrent()
            val observed = runCatching { fixture.listener.prepare() }.exceptionOrNull()
            assertTrue(observed === failure || observed?.cause === failure)
            assertEquals(0, fixture.refresher.refreshes)
            fixture.listener.stop(Mode.VPN)
            persistence.beforeOpen = null
            fixture.listener.start(Mode.VPN)
            withContext(Dispatchers.Default) { withTimeout(5_000L) { fixture.listener.prepare() } }
            assertEquals(stored, persistence.groups.groups().first())
            assertEquals(0, fixture.refresher.refreshes)
        }

    private suspend fun storage(): RoomProfileFixture =
        RoomProfileFixture(context, testPauseAuthority()).also {
            databases += it
            it.initialize()
        }

    private fun savedGroup(): ProxyGroup =
        ProxyGroup(
            "reserve",
            "Reserve",
            ProxyGroupType.SUBSCRIPTION,
            0,
            true,
            members =
                listOf(
                    ProxyProfile.Trojan("member", "Member", "reserve", "relay.example.test", 443, "fixture-password"),
                ),
        )

    private class Fixture(
        scope: CoroutineScope,
        persistence: RoomProfileFixture,
    ) {
        private val provider =
            ActiveSelectorSelectionProvider(
                persistence.groups,
                persistence.selectorSelections,
                persistence.selectorActiveGroup,
            )
        val refresher = RecordingRefresher()
        private val trigger =
            RelayActivationSelectorReloadTrigger(
                persistence.groups,
                persistence.selectorSelections,
                persistence.authority,
                FakeServiceStateStore(),
                refresher,
                provider,
            )
        private val coordinator = SelectorReloadCoordinator(scope, provider.selectionChanges(), trigger)
        val listener =
            SelectorReloadModule.provideSelectorReloadLifecycleListener(
                coordinator,
                trigger,
                persistence.groups,
            )
    }

    private class RecordingRefresher : RunningRelayRefresher {
        var refreshes = 0

        override suspend fun refresh(
            intent: RuntimePolicyReloadIntent,
            isCurrent: suspend () -> Boolean,
        ) {
            refreshes++
        }

        override suspend fun teardown() = Unit
    }
}
