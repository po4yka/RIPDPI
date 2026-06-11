package com.poyka.ripdpi.data

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
 * Test #3: Proxy-group read-modify-write race regression.
 *
 * Verifies that concurrent add() calls on SharedPreferencesProxyGroupRepository
 * do not lose writes. Without the Mutex, two coroutines could both read the same
 * stale list, compute independent next lists, and one would overwrite the other's
 * addition — leaving fewer groups than expected.
 */
@RunWith(RobolectricTestRunner::class)
class ProxyGroupRepositoryConcurrencyTest {
    private lateinit var repository: SharedPreferencesProxyGroupRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Use an isolated prefs file; clear before each test for independence.
        context
            .getSharedPreferences("proxy_group_concurrency_test", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        repository =
            SharedPreferencesProxyGroupRepository(
                context.also { ctx ->
                    // Shadow the production prefs name by pointing the repository at the
                    // test-scoped prefs file. Because SharedPreferencesProxyGroupRepository
                    // uses a fixed companion-object key "proxy_group_cache", we clear that
                    // prefs file directly to avoid cross-test contamination.
                    ctx
                        .getSharedPreferences("proxy_group_cache", Context.MODE_PRIVATE)
                        .edit()
                        .clear()
                        .commit()
                },
            )
    }

    @Test
    fun `concurrent add calls each preserve their group — no write is silently lost`() =
        runTest {
            val count = 50

            // Launch 50 concurrent coroutines each adding a distinct ProxyGroup.
            val jobs =
                (0 until count).map { i ->
                    launch(Dispatchers.Default) {
                        repository.add(
                            ProxyGroup(
                                id = "group-$i",
                                name = "Group $i",
                                type = ProxyGroupType.BASIC,
                                order = i,
                                isSelector = false,
                            ),
                        )
                    }
                }
            jobs.forEach { it.join() }

            val stored = repository.list()
            assertEquals(
                "All $count concurrent adds must be persisted; Mutex must serialize RMW",
                count,
                stored.size,
            )
        }
}
