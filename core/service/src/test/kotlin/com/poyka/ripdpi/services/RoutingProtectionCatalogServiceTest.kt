package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppRoutingPackageEntry
import com.poyka.ripdpi.data.AppRoutingPolicyCatalog
import com.poyka.ripdpi.data.AppRoutingPolicyPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `snapshot propagates per-package vpnDetection and severity`() {
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
                                            packages =
                                                listOf(
                                                    AppRoutingPackageEntry(
                                                        packageName = "ru.sberbankmobile",
                                                        vpnDetection = true,
                                                        detectionMethods = listOf("transport_vpn", "tun0_interface"),
                                                        severity = "high",
                                                    ),
                                                    AppRoutingPackageEntry(
                                                        packageName = "ru.rostel",
                                                        vpnDetection = false,
                                                        detectionMethods = emptyList(),
                                                        severity = "none",
                                                    ),
                                                ),
                                            detectionMethod = "transport_vpn",
                                            fixCoverage = "direct routing",
                                        ),
                                    ),
                            )
                    },
                installedPackagesProvider =
                    object : InstalledPackagesProvider {
                        override fun installedPackages(): Set<String> = setOf("ru.sberbankmobile", "ru.rostel")
                    },
            )

        val snapshot = service.snapshot()
        val sber = snapshot.detectedApps.single { it.packageName == "ru.sberbankmobile" }
        val gosuslugi = snapshot.detectedApps.single { it.packageName == "ru.rostel" }

        assertTrue(sber.vpnDetection)
        assertEquals(listOf("transport_vpn", "tun0_interface"), sber.packageDetectionMethods)
        assertEquals("high", sber.severity)
        assertEquals("transport_vpn", sber.detectionMethod)

        assertFalse(gosuslugi.vpnDetection)
        assertEquals(emptyList<String>(), gosuslugi.packageDetectionMethods)
        assertEquals("none", gosuslugi.severity)
        assertEquals("transport_vpn", gosuslugi.detectionMethod)
    }

    @Test
    fun `regex-matched packages default to vpnDetection false`() {
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
                                            packages =
                                                listOf(
                                                    AppRoutingPackageEntry(
                                                        packageName = "ru.sberbankmobile",
                                                        vpnDetection = true,
                                                        detectionMethods = listOf("transport_vpn"),
                                                        severity = "high",
                                                    ),
                                                ),
                                            packageRegexes = listOf("^ru\\.sberbank.*"),
                                            detectionMethod = "transport_vpn",
                                            fixCoverage = "direct routing",
                                        ),
                                    ),
                            )
                    },
                installedPackagesProvider =
                    object : InstalledPackagesProvider {
                        override fun installedPackages(): Set<String> =
                            setOf("ru.sberbankmobile", "ru.sberbank.sberkids")
                    },
            )

        val snapshot = service.snapshot()
        val sberkids = snapshot.detectedApps.single { it.packageName == "ru.sberbank.sberkids" }

        assertFalse(sberkids.vpnDetection)
        assertEquals(emptyList<String>(), sberkids.packageDetectionMethods)
        assertEquals("none", sberkids.severity)
    }
}
