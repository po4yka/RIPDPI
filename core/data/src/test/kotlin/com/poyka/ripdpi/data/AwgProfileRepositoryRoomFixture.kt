package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.awg.AwgActivationObfuscation
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before

internal abstract class AwgProfileRepositoryRoomFixture {
    protected lateinit var fixture: ProfileMutationTestFixture
    protected val dao get() = fixture.awgProfiles
    protected val credentialStore get() = fixture.awgCredentials
    protected val repository get() = fixture.awgRepository

    @Before
    fun setUp() =
        runBlocking(Dispatchers.IO) {
            fixture = ProfileMutationTestFixture()
            fixture.initialize()
        }

    @After
    fun tearDown() = fixture.close()

    protected fun runRoomTest(testBody: suspend TestScope.() -> Unit) =
        runTest {
            withContext(Dispatchers.IO) { testBody(this@runTest) }
        }

    protected fun failAwgWrites() {
        for (operation in listOf("INSERT", "UPDATE")) {
            fixture.room.database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_awg_$operation AFTER $operation ON awg_profiles " +
                    "BEGIN SELECT RAISE(ABORT, 'injected AWG write failure'); END",
            )
        }
    }

    protected fun allowAwgWrites() {
        for (operation in listOf("INSERT", "UPDATE")) {
            fixture.room.database.openHelper.writableDatabase
                .execSQL("DROP TRIGGER fail_awg_$operation")
        }
    }

    protected fun sampleRequest(profileId: String = "") =
        AwgActivationRequest(
            profileId = profileId,
            privateKey = "privkey==",
            peerPublicKey = "peerpub==",
            presharedKey = "psk==",
            endpointHost = "vpn.example.com",
            endpointPort = 51820,
            interfaceAddressV4 = "10.8.0.2/32",
            mtu = 1280,
            persistentKeepalive = 25,
            obfuscation = AwgActivationObfuscation(jc = 4, jmax = 70, h1 = "1000000001", i1 = "<b 0xdeadbeef>"),
        )
}
