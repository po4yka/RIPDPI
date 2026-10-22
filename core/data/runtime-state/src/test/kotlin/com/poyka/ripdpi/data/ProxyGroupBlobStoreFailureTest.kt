package com.poyka.ripdpi.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ProxyGroupBlobStoreFailureTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences("proxy_group_cache", Context.MODE_PRIVATE)

    @Before
    fun reset() {
        check(preferences.edit().clear().commit())
    }

    @Test
    fun `absent saved state is distinct from an unreadable sealed catalogue`() {
        val store = KeystoreProxyGroupBlobStore(context)
        assertNull(store.read())
        seedCorruptSealedCatalogue()
        val retained = preferences.all.toMap()

        assertThrows(IllegalArgumentException::class.java) { store.read() }

        assertEquals(retained, preferences.all)
    }

    @Test
    fun `unreadable sealed state never revives a legacy plaintext catalogue`() {
        seedCorruptSealedCatalogue()
        check(preferences.edit().putString("proxy-groups", "[]").commit())
        val retained = preferences.all.toMap()

        // Framing validation executes before AndroidKeyStore access. This tests
        // the production read boundary, not availability of the platform keystore.
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                KeystoreProxyGroupBlobStore(context).read()
            }

        assertEquals("Encrypted preferences payload framing is invalid", error.message)
        assertEquals(retained, preferences.all)
    }

    @Test
    fun `early collectors wait through strict read failures without losing unreadable bytes`() =
        runTest {
            seedCorruptSealedCatalogue()
            val retained = preferences.all.toMap()
            val authority = testPauseAuthority()
            val repository =
                SharedPreferencesProxyGroupRepository(
                    KeystoreProxyGroupBlobStore(context),
                    testMutationPreparationSource(authority),
                )
            val observed = mutableListOf<List<ProxyGroup>>()
            val collector =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    repository.groups().collect { observed += it }
                }
            assertTrue(collector.isActive)
            assertTrue(observed.isEmpty())
            assertEquals(retained, preferences.all)

            assertTrue(runCatching { repository.list() }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(collector.isActive)
            assertTrue(observed.isEmpty())
            assertTrue(
                runCatching { repository.replaceAll(authority.reserveStop(), emptyList()) }
                    .exceptionOrNull() is IllegalArgumentException,
            )
            assertEquals(retained, preferences.all)
        }

    private fun seedCorruptSealedCatalogue() {
        // Base64 encoding of a zero IV-length header; never a valid AES-GCM frame.
        check(preferences.edit().putString("proxy-groups-sealed", "AAAAAA==").commit())
    }
}
