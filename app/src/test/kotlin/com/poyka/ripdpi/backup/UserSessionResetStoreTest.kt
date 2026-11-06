package com.poyka.ripdpi.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.WsTunnelWorkerCredentialStore
import com.poyka.ripdpi.data.boot.SharedPreferencesBootSessionStateStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UserSessionResetStoreTest {
    @Test
    fun `clear removes session pointers resume state and worker credentials`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val boot =
                SharedPreferencesBootSessionStateStore(
                    context.getSharedPreferences("reset-session-test", Context.MODE_PRIVATE),
                )
            val worker = MemoryWorkerCredentials()
            boot.recordSession("profile", Mode.VPN)
            boot.setWasRunningAtUpdate(true)
            worker.save("worker", "secret")

            DefaultUserSessionResetStore(boot, worker).clearAll()

            assertNull(boot.lastSession())
            assertFalse(boot.wasRunningAtUpdate())
            assertNull(worker.load("worker"))
        }

    private class MemoryWorkerCredentials : WsTunnelWorkerCredentialStore {
        private val credentials = mutableMapOf<String, String>()

        override suspend fun load(credentialRef: String): String? = credentials[credentialRef]

        override suspend fun save(
            credentialRef: String,
            bearer: String,
        ) {
            credentials[credentialRef] = bearer
        }

        override suspend fun clear(credentialRef: String) {
            credentials.remove(credentialRef)
        }

        override suspend fun clearAll() = credentials.clear()
    }
}
