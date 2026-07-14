package com.poyka.ripdpi.backup

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class UserProfileResetStoreTest {
    @Test
    fun `clearAll invokes every durable user-data family`() =
        runTest {
            val calls = mutableListOf<String>()
            val store =
                DefaultUserProfileResetStore(
                    relay = RelayUserDataResetter { calls += "relay" },
                    warp = WarpUserDataResetter { calls += "warp" },
                    awg = AwgUserDataResetter { calls += "awg" },
                    xray = XrayUserDataResetter { calls += "xray" },
                    session = SessionUserDataResetter { calls += "session" },
                )

            store.clearAll()

            assertEquals(listOf("relay", "warp", "awg", "xray", "session"), calls)
        }
}
