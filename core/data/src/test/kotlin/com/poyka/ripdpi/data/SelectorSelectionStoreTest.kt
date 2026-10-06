package com.poyka.ripdpi.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.poyka.ripdpi.data.selector.SelectorActiveGroupStore
import com.poyka.ripdpi.data.selector.SharedPreferencesSelectorSelectionStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
    fun `automatic selection cannot overwrite a newer manual choice after ABA`() =
        kotlinx.coroutines.test.runTest {
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
    fun `automatic selection persists its origin and can replace a manual direct selection`() =
        kotlinx.coroutines.test.runTest {
            val store = newStore()
            store.select("group-a", "direct-member")
            assertTrue(
                restoredStore().snapshot("group-a").isManual,
            )

            assertTrue(store.selectAutomatically("group-a", store.snapshot("group-a"), "probe-winner"))

            val restored =
                restoredStore().snapshot("group-a")
            assertEquals("probe-winner", restored.profileId)
            assertFalse(restored.isManual)
        }

    @Test
    fun `same member manual reselection invalidates an in flight probe`() =
        kotlinx.coroutines.test.runTest {
            val store = newStore()
            store.select("group-a", "profile-1")
            val beforeProbe = store.snapshot("group-a")
            store.select("group-a", "profile-1")

            assertFalse(store.selectAutomatically("group-a", beforeProbe, "probe-winner"))
        }

    @Test
    fun `clearing selection or all selections invalidates empty snapshots`() =
        kotlinx.coroutines.test.runTest {
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
    fun `clear all invalidates an empty snapshot without creating a flow`() =
        runTest {
            val store = newStore()
            val beforeReset = store.snapshot("unobserved-empty-group")

            store.clearAll()

            assertFalse(store.selectAutomatically("unobserved-empty-group", beforeReset, "stale-winner"))
            assertNull(store.snapshot("unobserved-empty-group").profileId)
        }

    @Test
    fun `clear selection retires the checked manual receipt`() =
        runTest {
            val store = newStore()
            store.select("group-a", "manual-member")
            assertTrue(store.manualReceipt("group-a") != null)

            store.clearSelection("group-a")

            assertNull(store.manualReceipt("group-a"))
            assertNull(store.snapshot("group-a").profileId)
        }

    @Test
    fun `clear all retires every checked manual receipt`() =
        runTest {
            val store = newStore()
            store.select("group-a", "manual-a")
            store.select("group-b", "manual-b")
            assertTrue(store.manualReceipt("group-a") != null)
            assertTrue(store.manualReceipt("group-b") != null)

            store.clearAll()

            assertNull(store.manualReceipt("group-a"))
            assertNull(store.manualReceipt("group-b"))
            assertNull(store.snapshot("group-a").profileId)
            assertNull(store.snapshot("group-b").profileId)
        }

    @Test
    fun `legacy selections without provenance are not considered manual`() =
        kotlinx.coroutines.test.runTest {
            newStore()
            context
                .getSharedPreferences("selector_selection_store", Context.MODE_PRIVATE)
                .edit()
                .putString("selected-profile-group-a", "legacy-member")
                .commit()

            val restored =
                restoredStore().snapshot("group-a")

            assertEquals("legacy-member", restored.profileId)
            assertFalse(restored.isManual)
        }

    @Test
    fun `policy invalidation rejects old probes without changing manual selection`() =
        kotlinx.coroutines.test.runTest {
            val store = newStore()
            store.select("group-a", "manual-member")
            val beforePolicyChange = store.snapshot("group-a")

            store.invalidatePendingSelection("group-a")

            assertFalse(store.selectAutomatically("group-a", beforePolicyChange, "stale-winner"))
            assertEquals("manual-member", store.snapshot("group-a").profileId)
            assertTrue(store.snapshot("group-a").isManual)
            assertTrue(store.selectAutomatically("group-a", store.snapshot("group-a"), "fresh-winner"))
        }

    @Test
    fun `same checked member commit refreshes an already cached flow and snapshot without another write`() =
        runTest {
            val countedContext = CountingSelectorContext(context)
            val preparation = testMutationPreparationSource()
            val authority = preparation.authority
            val active = SelectorActiveGroupStore(countedContext, authority)
            val store =
                SharedPreferencesSelectorSelectionStore(
                    countedContext,
                    preparation,
                    authority,
                    active,
                )
            store.clearAll()
            store.select("group-a", "old-member")
            val before = store.snapshot("group-a")
            val flow = store.selectedProfileId("group-a")
            flow.test {
                assertEquals("old-member", awaitItem())
                val outcome =
                    authority.invalidateForMutation(
                        ProfileMutationOrigin.ExplicitActivation,
                        "checked-manual",
                        authority.reference(),
                    ) as ProfileMutationOutcome.Reserved
                val receipt = outcome.receipt as ProfileActivationReceipt
                val originalCommand = authority.snapshotAuthority()
                val commitsBefore = countedContext.commits
                val appliesBefore = countedContext.applies
                active.commitMember(
                    "group-a",
                    "new-member",
                    com.poyka.ripdpi.data.selector.SelectorChoiceOrigin
                        .Manual(receipt),
                )
                assertEquals("new-member", awaitItem())
                assertEquals("new-member", flow.value)
                val after = store.snapshot("group-a")
                assertEquals("new-member", after.profileId)
                assertTrue(after.isManual)
                assertTrue(after.revision > before.revision)
                assertEquals("group-a", active.activeGroupId.value)
                assertSame(receipt, store.manualReceipt("group-a"))
                assertEquals(originalCommand, authority.snapshotAuthority())
                assertEquals(commitsBefore + 1, countedContext.commits)
                assertEquals(appliesBefore, countedContext.applies)
                expectNoEvents()
                assertEquals(commitsBefore + 1, countedContext.commits)
                assertEquals(appliesBefore, countedContext.applies)
            }
        }

    @Test
    fun `repeated checked choice invalidates a stale probe when local revision is ahead`() =
        runTest {
            val preparation = testMutationPreparationSource()
            val authority = preparation.authority
            val active = SelectorActiveGroupStore(context, authority)
            val store =
                SharedPreferencesSelectorSelectionStore(
                    context,
                    preparation,
                    authority,
                    active,
                )
            store.clearAll()
            store.select("group-a", "same-member")
            repeat(2) { store.invalidatePendingSelection("group-a") }
            val stale = store.snapshot("group-a")
            val outcome =
                authority.invalidateForMutation(
                    ProfileMutationOrigin.ExplicitActivation,
                    "manual-again",
                    authority.reference(),
                ) as ProfileMutationOutcome.Reserved
            active.commitMember(
                "group-a",
                "same-member",
                com.poyka.ripdpi.data.selector.SelectorChoiceOrigin
                    .Manual(outcome.receipt as ProfileActivationReceipt),
            )
            assertFalse(store.selectAutomatically("group-a", stale, "stale-winner"))
            assertEquals("same-member", store.snapshot("group-a").profileId)
            assertTrue(store.snapshot("group-a").isManual)
            assertTrue(store.snapshot("group-a").revision > stale.revision)
        }

    @Test
    fun `automatic compare and write cannot overwrite a concurrent checked manual commit`() =
        runTest {
            val counted = CountingSelectorContext(context)
            val preparation = testMutationPreparationSource()
            val authority = preparation.authority
            val active = SelectorActiveGroupStore(counted, authority)
            val store =
                SharedPreferencesSelectorSelectionStore(
                    counted,
                    preparation,
                    authority,
                    active,
                )
            store.clearAll()
            store.select("group-a", "original-member")
            val expected = store.snapshot("group-a")
            val reserved =
                authority.invalidateForMutation(
                    ProfileMutationOrigin.ExplicitActivation,
                    "concurrent-measured",
                    authority.reference(),
                ) as ProfileMutationOutcome.Reserved
            val receipt = reserved.receipt as ProfileActivationReceipt
            val started = CountDownLatch(1)
            val completed = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val manual =
                Thread {
                    started.countDown()
                    try {
                        active.commitMember(
                            "group-a",
                            "checked-member",
                            com.poyka.ripdpi.data.selector.SelectorChoiceOrigin
                                .Manual(receipt),
                        )
                    } catch (problem: Throwable) {
                        failure.set(problem)
                    } finally {
                        completed.countDown()
                    }
                }
            counted.beforeCommit = {
                manual.start()
                assertTrue(started.await(2, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (completed.count != 0L && System.nanoTime() < deadline) {
                    when (manual.state) {
                        Thread.State.BLOCKED, Thread.State.WAITING -> break
                        else -> Thread.yield()
                    }
                }
                assertTrue(
                    completed.count == 0L || manual.state == Thread.State.BLOCKED ||
                        manual.state == Thread.State.WAITING,
                )
            }
            try {
                store.selectAutomatically("group-a", expected, "automatic-winner")
                assertTrue(completed.await(2, TimeUnit.SECONDS))
                failure.get()?.let { throw it }
                assertEquals("checked-member", store.snapshot("group-a").profileId)
                assertTrue(store.snapshot("group-a").isManual)
                assertSame(receipt, store.manualReceipt("group-a"))
            } finally {
                counted.beforeCommit = null
                manual.join(2_000)
                assertFalse(manual.isAlive)
            }
        }

    @Test
    fun `automatic choice clears the original transient manual receipt without another write`() =
        runTest {
            val counted = CountingSelectorContext(context)
            val preparation = testMutationPreparationSource()
            val active = SelectorActiveGroupStore(counted, preparation.authority)
            val store =
                SharedPreferencesSelectorSelectionStore(
                    counted,
                    preparation,
                    preparation.authority,
                    active,
                )
            store.clearAll()
            store.select("group-a", "manual-member")
            assertTrue(store.manualReceipt("group-a") != null)
            val before = counted.commits
            val appliesBefore = counted.applies
            assertTrue(store.selectAutomatically("group-a", store.snapshot("group-a"), "automatic-member"))
            assertEquals("automatic-member", store.snapshot("group-a").profileId)
            assertFalse(store.snapshot("group-a").isManual)
            assertNull(store.manualReceipt("group-a"))
            assertEquals(before + 1, counted.commits)
            assertEquals(appliesBefore, counted.applies)
        }

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun restoredStore(): SharedPreferencesSelectorSelectionStore {
        val preparation = testMutationPreparationSource()
        return SharedPreferencesSelectorSelectionStore(
            context,
            preparation,
            preparation.authority,
            SelectorActiveGroupStore(context, preparation.authority),
        )
    }

    private fun newStore(): SharedPreferencesSelectorSelectionStore {
        val store =
            restoredStore()
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
            val restarted =
                restoredStore()

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

private class CountingSelectorContext(
    base: Context,
) : ContextWrapper(base) {
    var commits = 0
        private set
    var applies = 0
        private set
    var beforeCommit: (() -> Unit)? = null

    override fun getSharedPreferences(
        name: String,
        mode: Int,
    ): SharedPreferences {
        val actual = super.getSharedPreferences(name, mode)
        return object : SharedPreferences by actual {
            override fun edit(): SharedPreferences.Editor {
                val editor = actual.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(
                        key: String,
                        value: String?,
                    ): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }

                    override fun putStringSet(
                        key: String,
                        values: Set<String>?,
                    ): SharedPreferences.Editor {
                        editor.putStringSet(key, values)
                        return this
                    }

                    override fun putInt(
                        key: String,
                        value: Int,
                    ): SharedPreferences.Editor {
                        editor.putInt(key, value)
                        return this
                    }

                    override fun putLong(
                        key: String,
                        value: Long,
                    ): SharedPreferences.Editor {
                        editor.putLong(key, value)
                        return this
                    }

                    override fun putFloat(
                        key: String,
                        value: Float,
                    ): SharedPreferences.Editor {
                        editor.putFloat(key, value)
                        return this
                    }

                    override fun putBoolean(
                        key: String,
                        value: Boolean,
                    ): SharedPreferences.Editor {
                        editor.putBoolean(key, value)
                        return this
                    }

                    override fun remove(key: String): SharedPreferences.Editor {
                        editor.remove(key)
                        return this
                    }

                    override fun clear(): SharedPreferences.Editor {
                        editor.clear()
                        return this
                    }

                    override fun apply() {
                        applies += 1
                        editor.apply()
                    }

                    override fun commit(): Boolean {
                        val hook = beforeCommit
                        beforeCommit = null
                        hook?.invoke()
                        commits += 1
                        return editor.commit()
                    }
                }
            }
        }
    }
}
