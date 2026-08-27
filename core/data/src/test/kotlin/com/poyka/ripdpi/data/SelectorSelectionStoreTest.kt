package com.poyka.ripdpi.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.poyka.ripdpi.data.selector.SharedPreferencesSelectorSelectionStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [SharedPreferencesSelectorSelectionStore]: the selected-member
 * signal for selector [ProxyGroup]s. Exposes a `selectedProfileId` flow per
 * group and persists the last selection so a service restart resumes the
 * last-selected member rather than the first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SelectorSelectionStoreTest {
    @Test
    fun `automatic selection cannot overwrite a newer manual choice after ABA`() {
        val store = newStore()
        store.select("group-a", "profile-1")
        val beforeProbe = store.snapshot("group-a")
        store.select("group-a", "profile-2")
        store.select("group-a", "profile-1")

        val applied = store.selectAutomatically("group-a", beforeProbe, "probe-winner")

        assertFalse(applied)
        assertEquals("profile-1", store.selectedProfileId("group-a").value)
        assertTrue(store.snapshot("group-a").isManual)
    }

    @Test
    fun `automatic selection persists its origin and can replace a manual direct selection`() {
        val store = newStore()
        store.select("group-a", "direct-member")
        assertTrue(SharedPreferencesSelectorSelectionStore(context).snapshot("group-a").isManual)

        assertTrue(store.selectAutomatically("group-a", store.snapshot("group-a"), "probe-winner"))

        val restored = SharedPreferencesSelectorSelectionStore(context).snapshot("group-a")
        assertEquals("probe-winner", restored.profileId)
        assertFalse(restored.isManual)
    }

    @Test
    fun `same member manual reselection invalidates an in flight probe`() {
        val store = newStore()
        store.select("group-a", "profile-1")
        val beforeProbe = store.snapshot("group-a")
        store.select("group-a", "profile-1")

        assertFalse(store.selectAutomatically("group-a", beforeProbe, "probe-winner"))
    }

    @Test
    fun `clearing selection or all selections invalidates empty snapshots`() {
        val store = newStore()
        val beforeClear = store.snapshot("group-a")
        store.clearSelection("group-a")
        assertFalse(store.selectAutomatically("group-a", beforeClear, "probe-winner"))
        val beforeReset = store.snapshot("group-a")
        store.clearAll()
        assertFalse(store.selectAutomatically("group-a", beforeReset, "probe-winner"))
        assertNull(store.snapshot("group-a").profileId)
        assertFalse(store.snapshot("group-a").isManual)
    }

    @Test
    fun `legacy selections without provenance are not considered manual`() {
        newStore()
        context
            .getSharedPreferences("selector_selection_store", Context.MODE_PRIVATE)
            .edit()
            .putString("selected-profile-group-a", "legacy-member")
            .commit()

        val restored = SharedPreferencesSelectorSelectionStore(context).snapshot("group-a")

        assertEquals("legacy-member", restored.profileId)
        assertFalse(restored.isManual)
    }

    @Test
    fun `policy invalidation rejects old probes without changing manual selection`() {
        val store = newStore()
        store.select("group-a", "manual-member")
        val beforePolicyChange = store.snapshot("group-a")

        store.invalidatePendingSelection("group-a")

        assertFalse(store.selectAutomatically("group-a", beforePolicyChange, "stale-winner"))
        assertEquals("manual-member", store.snapshot("group-a").profileId)
        assertTrue(store.snapshot("group-a").isManual)
        assertTrue(store.selectAutomatically("group-a", store.snapshot("group-a"), "fresh-winner"))
    }

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun newStore(): SharedPreferencesSelectorSelectionStore {
        val store = SharedPreferencesSelectorSelectionStore(context)
        store.clearAll()
        return store
    }

    @Test
    fun `selectedProfileId is null for a group with no selection yet`() =
        runTest {
            val store = newStore()

            assertNull(store.selectedProfileId("group-a").value)
        }

    @Test
    fun `select then read returns the selected member id`() =
        runTest {
            val store = newStore()

            store.select(groupId = "group-a", profileId = "profile-1")

            assertEquals("profile-1", store.selectedProfileId("group-a").value)
        }

    @Test
    fun `selection is scoped per group`() =
        runTest {
            val store = newStore()

            store.select(groupId = "group-a", profileId = "profile-1")
            store.select(groupId = "group-b", profileId = "profile-2")

            assertEquals("profile-1", store.selectedProfileId("group-a").value)
            assertEquals("profile-2", store.selectedProfileId("group-b").value)
        }

    @Test
    fun `selectedProfileId flow emits on every selection change`() =
        runTest {
            val store = newStore()

            store.selectedProfileId("group-a").test {
                assertNull(awaitItem())

                store.select(groupId = "group-a", profileId = "profile-1")
                assertEquals("profile-1", awaitItem())

                store.select(groupId = "group-a", profileId = "profile-2")
                assertEquals("profile-2", awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `selection survives a fresh store instance backed by the same prefs`() =
        runTest {
            newStore().select(groupId = "group-a", profileId = "profile-resumed")

            // A new instance simulates a service restart reading from disk.
            val restarted = SharedPreferencesSelectorSelectionStore(context)

            assertEquals("profile-resumed", restarted.selectedProfileId("group-a").value)
        }

    @Test
    fun `clearSelection drops the persisted selection for a group`() =
        runTest {
            val store = newStore()
            store.select(groupId = "group-a", profileId = "profile-1")

            store.clearSelection("group-a")

            assertNull(store.selectedProfileId("group-a").value)
        }
}
