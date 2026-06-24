package com.poyka.ripdpi.data

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
        // An in-memory blob store gives each test an isolated, AndroidKeyStore-free
        // backing for the repository under test.
        repository = SharedPreferencesProxyGroupRepository(FakeProxyGroupBlobStore())
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
