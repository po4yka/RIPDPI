package com.poyka.ripdpi.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.awg.KeystoreAwgCredentialStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** An unreadable sealed credential must not look absent, or a later save overwrites the real secret. */
@RunWith(RobolectricTestRunner::class)
class KeystoreCredentialUnreadableTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `unreadable relay credentials fail the load instead of reading as absent`() =
        runTest {
            val preferences = seedCorrupt("relay_credentials_secure", "relay-credentials:primary")

            val failure = runCatching { KeystoreRelayCredentialStore(context).load("primary") }

            assertTrue(
                "expected an unreadable credential, got $failure",
                failure.exceptionOrNull() is UnreadableCredentialException,
            )
            assertEquals(CorruptPayload, preferences.getString("relay-credentials:primary", null))
        }

    @Test
    fun `a replacing read opens an unreadable credential as empty`() =
        runTest {
            seedCorrupt("relay_credentials_secure", "relay-credentials:primary")
            val store = KeystoreRelayCredentialStore(context)

            assertNull(loadReplaceableCredential { store.load("primary") })
        }

    @Test
    fun `unreadable awg secrets fail the load instead of reading as absent`() =
        runTest {
            seedCorrupt("awg_credentials_secure", "awg_credentials:primary")

            val failure = runCatching { KeystoreAwgCredentialStore(context).load("primary") }

            assertTrue("expected a failure, got ${failure.getOrNull()}", failure.isFailure)
        }

    private fun seedCorrupt(
        prefsName: String,
        key: String,
    ) = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).also { preferences ->
        val editor = preferences.edit()
        check(editor.clear().putString(key, CorruptPayload).commit())
    }

    private companion object {
        // Base64 encoding of a zero IV-length header; never a valid AES-GCM frame.
        const val CorruptPayload = "AAAAAA=="
    }
}
