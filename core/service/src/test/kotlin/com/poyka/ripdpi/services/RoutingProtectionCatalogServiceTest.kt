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
        assertEquals("transport_vpn", sberkids.detectionMethod)
    }

    @Test
    fun `snapshot preserves duplicate preset order and first package metadata`() {
        val service =
            DefaultRoutingProtectionCatalogService(
                appRoutingCatalogProvider =
                    object : AppRoutingCatalogProvider {
                        override fun load(): AppRoutingPolicyCatalog =
                            AppRoutingPolicyCatalog(
                                presets =
                                    listOf(
                                        AppRoutingPolicyPreset(
                                            id = "duplicate",
                                            title = "First",
                                            packages =
                                                listOf(
                                                    AppRoutingPackageEntry(
                                                        packageName = "com.alpha",
                                                        vpnDetection = true,
                                                        detectionMethods = listOf("first-package"),
                                                        severity = "high",
                                                    ),
                                                    AppRoutingPackageEntry(
                                                        packageName = "com.alpha",
                                                        vpnDetection = false,
                                                        detectionMethods = listOf("second-package"),
                                                        severity = "low",
                                                    ),
                                                    AppRoutingPackageEntry(packageName = "com.shared"),
                                                ),
                                            detectionMethod = "first-preset",
                                            fixCoverage = "first coverage",
                                        ),
                                        AppRoutingPolicyPreset(
                                            id = "duplicate",
                                            title = "Second",
                                            packages =
                                                listOf(
                                                    AppRoutingPackageEntry(packageName = "com.beta"),
                                                    AppRoutingPackageEntry(packageName = "com.alpha"),
                                                ),
                                            detectionMethod = "second-preset",
                                            fixCoverage = "second coverage",
                                        ),
                                    ),
                            )
                    },
                installedPackagesProvider =
                    object : InstalledPackagesProvider {
                        override fun installedPackages(): Set<String> = emptySet()
                    },
            )

        val snapshot = service.snapshot()

        assertEquals(listOf("First", "Second"), snapshot.presets.map { it.title })
        assertEquals(listOf("com.alpha", "com.shared"), snapshot.presets[0].matchedPackages)
        assertEquals(listOf("com.alpha", "com.beta"), snapshot.presets[1].matchedPackages)
        assertEquals(
            listOf("com.alpha:First", "com.alpha:Second", "com.beta:Second", "com.shared:First"),
            snapshot.detectedApps.map { "${it.packageName}:${it.presetTitle}" },
        )

        val alpha = snapshot.detectedApps.first { it.packageName == "com.alpha" }
        assertTrue(alpha.vpnDetection)
        assertEquals("first-package", alpha.detectionMethod)
        assertEquals(listOf("first-package"), alpha.packageDetectionMethods)
        assertEquals("high", alpha.severity)
    }

    @Test
    fun `snapshot refreshes installed package regex matches when catalog index is cached`() {
        val installedPackagesProvider =
            MutableRoutingProtectionInstalledPackagesProvider(
                setOf("com.example.one", "org.other"),
            )
        val service =
            DefaultRoutingProtectionCatalogService(
                appRoutingCatalogProvider =
                    object : AppRoutingCatalogProvider {
                        override fun load(): AppRoutingPolicyCatalog =
                            AppRoutingPolicyCatalog(
                                presets =
                                    listOf(
                                        AppRoutingPolicyPreset(
                                            id = "regex",
                                            title = "Regex",
                                            packageRegexes = listOf("^com\\.example\\..+"),
                                        ),
                                    ),
                            )
                    },
                installedPackagesProvider = installedPackagesProvider,
            )

        assertEquals(
            listOf("com.example.one"),
            service
                .snapshot()
                .presets
                .single()
                .matchedPackages,
        )

        installedPackagesProvider.packages = setOf("com.example.two", "org.other")

        assertEquals(
            listOf("com.example.two"),
            service
                .snapshot()
                .presets
                .single()
                .matchedPackages,
        )
    }
}

private class MutableRoutingProtectionInstalledPackagesProvider(
    var packages: Set<String>,
) : InstalledPackagesProvider {
    override fun installedPackages(): Set<String> = packages
}
