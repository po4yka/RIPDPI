package com.poyka.ripdpi.proxyimport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.subscription.SingBoxParseResult
import com.poyka.ripdpi.data.subscription.SingBoxSubscriptionParser
import com.poyka.ripdpi.data.testPauseAuthority
import com.poyka.ripdpi.data.testing.RoomProfileFixture
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PasswordRelayImportAdmissionTest {
    private val databases = mutableListOf<RoomProfileFixture>()

    @After
    fun closeStorage() = databases.forEach { it.close() }

    @Test
    fun `invalid sing-box password relay ports cannot replace the active Room profile`() =
        runTest {
            val storage = storage()
            val activator = RelayProfileActivator(storage.coordinator)
            activator.activate(
                activator.captureMutation(ProfileMutationOrigin.ExplicitActivation),
                ProxyProfile.Trojan("prior", "Prior", "group", "prior.example", 443, "prior-fixture"),
            )
            val settings = storage.settings.snapshot()
            val profile = storage.relayProfiles.load(DefaultRelayProfileId)
            val credentials = storage.relayCredentials.load(DefaultRelayProfileId)
            val selection = storage.xraySelection.current()
            for (kind in listOf("trojan", "anytls")) {
                for (port in listOf(-1, 0, 65536)) {
                    val payload =
                        """{"outbounds":[{"type":"$kind","server":"invalid.example","server_port":$port,
                        "password":"fixture-password","tls":{"enabled":true}}]}"""
                    val parsed = SingBoxSubscriptionParser.parse(payload, "group") as SingBoxParseResult.Success
                    val imported = parsed.profiles.single()

                    assertTrue(
                        "$kind port $port",
                        runCatching {
                            activator.activate(
                                activator.captureMutation(ProfileMutationOrigin.ExplicitActivation),
                                imported,
                            )
                        }.isFailure,
                    )

                    assertEquals(settings, storage.settings.snapshot())
                    assertEquals(profile, storage.relayProfiles.load(DefaultRelayProfileId))
                    assertEquals(credentials, storage.relayCredentials.load(DefaultRelayProfileId))
                    assertEquals(selection, storage.xraySelection.current())
                }
            }
        }

    private suspend fun storage(): RoomProfileFixture =
        RoomProfileFixture(ApplicationProvider.getApplicationContext<Context>(), testPauseAuthority()).also {
            databases += it
            it.initialize()
        }
}
