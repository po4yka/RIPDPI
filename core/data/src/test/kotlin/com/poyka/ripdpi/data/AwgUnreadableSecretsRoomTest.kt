package com.poyka.ripdpi.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.awg.AwgActivationObfuscation
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.awg.TestDirectAwgProfileMutationCoordinator
import com.poyka.ripdpi.data.rules.RipDpiDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An AWG profile whose sealed secrets no longer decrypt stays visible so the user can re-enter its key. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AwgUnreadableSecretsRoomTest {
    private val db =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RipDpiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val credentialStore = InMemoryAwgCredentialStore()
    private val repository =
        AwgProfileRepository(
            db.awgProfileDao(),
            credentialStore,
            TestDirectAwgProfileMutationCoordinator(db.awgProfileDao(), credentialStore),
        )

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a profile whose secrets cannot be decrypted still lists and accepts re-entered keys`() =
        runTest {
            val request =
                AwgActivationRequest(
                    profileId = "",
                    privateKey = "privkey==",
                    peerPublicKey = "peerpub==",
                    presharedKey = "psk==",
                    endpointHost = "vpn.example.com",
                    endpointPort = 51820,
                    interfaceAddressV4 = "10.8.0.2/32",
                    mtu = 1280,
                    persistentKeepalive = 25,
                    obfuscation = AwgActivationObfuscation(jc = 4, jmax = 70, h1 = "1000000001"),
                )
            val id = repository.save(name = "home", request = request)
            credentialStore.unreadable += id

            assertEquals("", requireNotNull(repository.load(id)).request.privateKey)
            assertEquals(listOf(id), repository.observeProfiles().first().map { it.id })

            repository.save("home", request.copy(privateKey = "re-entered=="), existingId = id)
            assertEquals("re-entered==", repository.load(id)?.request?.privateKey)
        }
}
