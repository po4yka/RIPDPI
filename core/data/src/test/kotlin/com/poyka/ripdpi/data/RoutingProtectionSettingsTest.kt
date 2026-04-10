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
