package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppRoutingPolicyCatalog
import com.poyka.ripdpi.data.AppRoutingPolicyPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingProtectionCatalogServiceTest {
    @Test
    fun `snapshot resolves installed packages and flattens detected apps`() {
        val service =
            DefaultRoutingProtectionCatalogService(
                appRoutingCatalogProvider =
                    object : AppRoutingCatalogProvider {
                        override fun load(): AppRoutingPolicyCatalog =
                            AppRoutingPolicyCatalog(
                                presets =
                                    listOf(
                                        AppRoutingPolicyPreset(
                                            id = "ru-apps",
                                            title = "Russian apps",
                                            exactPackages = listOf("ru.example.bank"),
                                            packageRegexes = listOf("^ru\\.yandex\\..+"),
                                            detectionMethod = "transport_vpn",
                                            fixCoverage = "direct routing",
                                            limitations = "vpn transport still visible",
                                        ),
                                    ),
                            )
                    },
                installedPackagesProvider =
                    object : InstalledPackagesProvider {
                        override fun installedPackages(): Set<String> =
                            setOf("ru.example.bank", "ru.yandex.music", "com.example.safe")
                    },
            )

        val snapshot = service.snapshot()

        assertEquals(listOf("ru.example.bank", "ru.yandex.music"), snapshot.presets.single().matchedPackages)
        assertEquals(2, snapshot.detectedApps.size)
        assertTrue(snapshot.detectedApps.any { it.packageName == "ru.yandex.music" })
    }
}
