package com.poyka.ripdpi.diagnostics

import android.content.ContextWrapper
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.services.RoutingProtectionCatalogService
import com.poyka.ripdpi.services.RoutingProtectionCatalogSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HomeNetworkMissingCapabilitiesTest {
    @Test
    fun `missing network capabilities do not claim captive portal absence`() =
        runTest {
            val context =
                object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                    override fun getSystemService(name: String): Any? = null
                }
            val source =
                DefaultHomeAnalysisAugmentationSource(
                    context = context,
                    networkFingerprintProvider =
                        object : NetworkFingerprintProvider {
                            override fun capture(): NetworkFingerprint? = null
                        },
                    catalogService =
                        object : RoutingProtectionCatalogService {
                            override fun snapshot() = RoutingProtectionCatalogSnapshot()
                        },
                )
            val character = source.networkCharacter()
            assertNotNull(character)
            assertNull(character?.captivePortalDetected)
        }
}
