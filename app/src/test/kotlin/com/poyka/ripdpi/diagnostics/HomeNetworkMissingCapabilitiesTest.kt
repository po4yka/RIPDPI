package com.poyka.ripdpi.diagnostics

import android.content.ContextWrapper
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.services.RoutingProtectionCatalogService
import com.poyka.ripdpi.services.RoutingProtectionCatalogSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HomeNetworkMissingCapabilitiesTest {
    @Test
    fun `Home uses a confirmed physical portal even with no app default network capabilities`() =
        runTest {
            val character = source(physicalNetwork(validated = false, portal = true)).networkCharacter()

            assertEquals(true, character?.captivePortalDetected)
        }

    @Test
    fun `Home retains Android validation separately for physical networks without a portal`() =
        runTest {
            for (validated in listOf(false, true)) {
                val character = source(physicalNetwork(validated, portal = false)).networkCharacter()

                assertEquals(false, character?.captivePortalDetected)
                assertEquals(validated, character?.networkValidated)
            }
        }

    @Test
    fun `missing network capabilities do not claim captive portal absence`() =
        runTest {
            val character = source(null).networkCharacter()
            assertNotNull(character)
            assertNull(character?.captivePortalDetected)
            assertNull(character?.networkValidated)
        }

    private fun source(fingerprint: NetworkFingerprint?) =
        DefaultHomeAnalysisAugmentationSource(
            context =
                object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                    override fun getSystemService(name: String): Any? = null
                },
            networkFingerprintProvider =
                object : NetworkFingerprintProvider {
                    override fun capture(): NetworkFingerprint? = fingerprint
                },
            catalogService =
                object : RoutingProtectionCatalogService {
                    override fun snapshot() = RoutingProtectionCatalogSnapshot()
                },
        )

    private fun physicalNetwork(
        validated: Boolean,
        portal: Boolean,
    ) = NetworkFingerprint(
        transport = "wifi",
        networkValidated = validated,
        captivePortalDetected = portal,
        privateDnsMode = "opportunistic",
        dnsServers = emptyList(),
    )
}
