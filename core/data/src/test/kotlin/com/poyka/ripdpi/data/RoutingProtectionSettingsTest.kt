package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingProtectionSettingsTest {
    @Test
    fun `catalog resolves exact and regex package matches`() {
        val catalog =
            appRoutingPolicyCatalogFromJson(
                """
                {
                  "presets": [
                    {
                      "id": "sample",
                      "title": "Sample",
                      "exactPackages": ["com.exact.app"],
                      "packageRegexes": ["^ru\\.yandex\\..+"]
                    }
                  ]
                }
                """.trimIndent(),
            )

        val resolved =
            catalog.presets
                .single()
                .resolvePackages(
                    setOf("com.exact.app", "ru.yandex.music", "ru.yandex.maps", "com.other"),
                )

        assertEquals(setOf("com.exact.app", "ru.yandex.music", "ru.yandex.maps"), resolved)
    }

    @Test
    fun `legacy exclude russian apps migrates to default preset on read`() {
        val settings =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .clearAppRoutingEnabledPresetIds()
                .setExcludeRussianAppsEnabled(true)
                .build()

        assertEquals(listOf(DefaultAppRoutingRussianPresetId), settings.effectiveAppRoutingEnabledPresetIds())
    }

    @Test
    fun `explicit app routing presets override legacy migration`() {
        val settings =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .clearAppRoutingEnabledPresetIds()
                .addAppRoutingEnabledPresetIds("custom")
                .setExcludeRussianAppsEnabled(false)
                .build()

        assertEquals(listOf("custom"), settings.effectiveAppRoutingEnabledPresetIds())
        assertTrue(settings.effectiveAppRoutingEnabledPresetIds().none { it == DefaultAppRoutingRussianPresetId })
    }

    @Test
    fun `packages list takes precedence over exactPackages in resolvePackages`() {
        val catalog =
            appRoutingPolicyCatalogFromJson(
                """
                {
                  "presets": [
                    {
                      "id": "test",
                      "title": "Test",
                      "exactPackages": ["com.old.app"],
                      "packages": [
                        {"package": "com.new.app", "vpnDetection": true, "detectionMethods": ["transport_vpn"], "severity": "high"}
                      ]
                    }
                  ]
                }
                """.trimIndent(),
            )

        val resolved = catalog.presets.single().resolvePackages(setOf("com.old.app", "com.new.app"))

        assertTrue(resolved.contains("com.new.app"))
        assertTrue(!resolved.contains("com.old.app"))
    }

    @Test
    fun `resolvePackages falls back to exactPackages when packages is empty`() {
        val catalog =
            appRoutingPolicyCatalogFromJson(
                """
                {
                  "presets": [
                    {
                      "id": "test",
                      "title": "Test",
                      "exactPackages": ["com.legacy.app"],
                      "packages": []
                    }
                  ]
                }
                """.trimIndent(),
            )

        val resolved = catalog.presets.single().resolvePackages(setOf("com.legacy.app"))

        assertTrue(resolved.contains("com.legacy.app"))
    }

    @Test
    fun `findPackageEntry returns metadata for known package`() {
        val catalog =
            appRoutingPolicyCatalogFromJson(
                """
                {
                  "presets": [
                    {
                      "id": "test",
                      "title": "Test",
                      "packages": [
                        {"package": "ru.sberbankmobile", "vpnDetection": true, "detectionMethods": ["transport_vpn", "tun0_interface"], "severity": "high"},
                        {"package": "ru.rostel", "vpnDetection": false, "detectionMethods": [], "severity": "none"}
                      ]
                    }
                  ]
                }
                """.trimIndent(),
            )

        val preset = catalog.presets.single()
        val sber = preset.findPackageEntry("ru.sberbankmobile")
        val gosuslugi = preset.findPackageEntry("ru.rostel")
        val unknown = preset.findPackageEntry("com.unknown")

        assertEquals(true, sber?.vpnDetection)
        assertEquals(listOf("transport_vpn", "tun0_interface"), sber?.detectionMethods)
        assertEquals("high", sber?.severity)
        assertEquals(false, gosuslugi?.vpnDetection)
        assertEquals(emptyList<String>(), gosuslugi?.detectionMethods)
        assertEquals(null, unknown)
    }

    @Test
    fun `expanded regexes match subsidiary packages and reject unrelated`() {
        val catalog =
            appRoutingPolicyCatalogFromJson(
                """
                {
                  "presets": [
                    {
                      "id": "test",
                      "title": "Test",
                      "exactPackages": [],
                      "packageRegexes": [
                        "^ru\\.ozon\\..+",
                        "^ru\\.sberbank.*",
                        "^ru\\.vtb.*",
                        "^com\\.wildberries\\..+",
                        "^ru\\.beeline\\..+",
                        "^ru\\.tele2\\..+",
                        "^ru\\.tinkoff\\..+",
                        "^com\\.vk\\..+",
                        "^ru\\.ok\\..+",
                        "^ru\\.mail\\..+",
                        "^ru\\.mos\\..+",
                        "^ru\\.rt\\..+",
                        "^ru\\.magnit\\..+",
                        "^ru\\.rshb\\..+"
                      ]
                    }
                  ]
                }
                """.trimIndent(),
            )

        val installed =
            setOf(
                "ru.ozon.seller_app",
                "ru.sberbankmobile",
                "ru.sberbank.sberkids",
                "ru.vtb24.mobilebanking.android",
                "ru.vtb.invest",
                "com.wildberries.consultations",
                "ru.beeline.services",
                "ru.tele2.mytele2",
                "ru.tinkoff.investing",
                "com.vk.vkvideo",
                "ru.ok.dating",
                "ru.mail.cloud",
                "ru.mos.app",
                "ru.rt.life",
                "ru.magnit.tag",
                "ru.rshb.dbo",
                "com.unrelated.app",
                "org.example.other",
            )

        val resolved = catalog.presets.single().resolvePackages(installed)

        assertTrue(resolved.contains("ru.ozon.seller_app"))
        assertTrue(resolved.contains("ru.sberbankmobile"))
        assertTrue(resolved.contains("ru.sberbank.sberkids"))
        assertTrue(resolved.contains("ru.vtb24.mobilebanking.android"))
        assertTrue(resolved.contains("ru.vtb.invest"))
        assertTrue(resolved.contains("com.wildberries.consultations"))
        assertTrue(resolved.contains("ru.beeline.services"))
        assertTrue(resolved.contains("ru.tele2.mytele2"))
        assertTrue(resolved.contains("ru.tinkoff.investing"))
        assertTrue(resolved.contains("com.vk.vkvideo"))
        assertTrue(resolved.contains("ru.ok.dating"))
        assertTrue(resolved.contains("ru.mail.cloud"))
        assertTrue(resolved.contains("ru.mos.app"))
        assertTrue(resolved.contains("ru.rt.life"))
        assertTrue(resolved.contains("ru.magnit.tag"))
        assertTrue(resolved.contains("ru.rshb.dbo"))
        assertTrue(!resolved.contains("com.unrelated.app"))
        assertTrue(!resolved.contains("org.example.other"))
        assertEquals(16, resolved.size)
    }

    @Test
    fun `dht trigger cidr catalog parses bundled cidrs`() {
        val catalog =
            dhtTriggerCidrsCatalogFromJson(
                """
                {
                  "cidrs": [
                    "134.195.196.0/22",
                    "62.210.0.0/17"
                  ]
                }
                """.trimIndent(),
            )

        assertEquals(listOf("134.195.196.0/22", "62.210.0.0/17"), catalog.cidrs)
    }
}
