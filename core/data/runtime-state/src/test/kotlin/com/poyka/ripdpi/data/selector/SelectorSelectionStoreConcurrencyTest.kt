package com.poyka.ripdpi.data.selector

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Test #4: Selector store — concurrent select/clearSelection must not produce
 * a state where the flow value diverges from the persisted SharedPreferences value.
 *
 * Without the synchronized(flows) guard on select/clearSelection, two threads could
 * interleave: thread A writes prefs then thread B clears prefs then thread B sets
 * flow=null then thread A sets flow="p1" — leaving prefs cleared but flow="p1".
 */
@RunWith(RobolectricTestRunner::class)
class SelectorSelectionStoreConcurrencyTest {
    private lateinit var store: SharedPreferencesSelectorSelectionStore
    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Clear the store's prefs file before each test.
        context
            .getSharedPreferences("selector_selection_store", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        store = SharedPreferencesSelectorSelectionStore(context)
        prefs = context.getSharedPreferences("selector_selection_store", Context.MODE_PRIVATE)
    }

    @Test
    fun `concurrent select and clearSelection produce no exception and consistent final state`() =
        runTest {
            val groupId = "group-A"
            val profileId = "profile-1"
            val iterations = 500

            // Two threads: one alternates select/clearSelection, the other does the same.
            val job1 =
                launch(Dispatchers.Default) {
                    repeat(iterations) { i ->
                        if (i % 2 == 0) {
                            store.select(groupId, profileId)
                        } else {
                            store.clearSelection(groupId)
                        }
                    }
                }
            val job2 =
                launch(Dispatchers.Default) {
                    repeat(iterations) { i ->
                        if (i % 3 == 0) {
                            store.clearSelection(groupId)
                        } else {
                            store.select(groupId, profileId)
                        }
                    }
                }

            job1.join()
            job2.join()

            // After all writes complete, the in-memory flow value must match the
            // persisted SharedPreferences value. A divergence means the lock failed.
            val flowValue = store.selectedProfileId(groupId).value
            val prefKey = "selected-profile-$groupId"
            val persistedValue = prefs.getString(prefKey, null)

            assertEquals(
                "Flow value must match persisted SharedPreferences value after concurrent writes",
                persistedValue,
                flowValue,
            )
        }
}
