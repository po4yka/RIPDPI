package com.poyka.ripdpi.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsJsonSerializationTest {
    @Test
    fun `flat snapshot preserves values from every section`() {
        val payload =
            """
            {
              "formatVersion": 1,
              "appTheme": "dark",
              "mode": "proxy",
              "dnsMode": "plain",
              "dnsIp": "9.9.9.9",
              "proxyPort": 2080,
              "commandLineArgs": "--host=тест.example\n--label=\"quoted\"",
              "tcpChainSteps": [{"kind":"split","marker":"1"}],
              "evolutionEpsilon": 0.25,
              "warpProfileId": "warp-profile",
              "relayAppsScriptScriptIds": ["script-one", "script-two"],
              "antiCorrelationEnabled": true
            }
            """.trimIndent()

        val exported = appSettingsFromJson(payload).toJson()
        val root = appSettingsJson.parseToJsonElement(exported).jsonObject
        val restored = appSettingsFromJson(exported)

        assertEquals(JsonPrimitive(1), root["formatVersion"])
        assertEquals(JsonPrimitive("proxy"), root["mode"])
        assertEquals(JsonPrimitive(2080), root["proxyPort"])
        assertEquals(1, root.getValue("tcpChainSteps").jsonArray.size)
        for (section in listOf(
            "rootUiRuntime",
            "dns",
            "proxyDesync",
            "chains",
            "quicAdaptive",
            "warp",
            "relay",
            "routing",
        )) {
            assertFalse("Unexpected section wrapper: $section", section in root)
        }
        assertEquals("dark", restored.appTheme)
        assertEquals("proxy", restored.ripdpiMode)
        assertEquals("9.9.9.9", restored.dnsIp)
        assertEquals(2080, restored.proxyPort)
        assertEquals("--host=тест.example\n--label=\"quoted\"", restored.cmdArgs)
        assertEquals("split", restored.tcpChainStepsList.single().kind)
        assertEquals("1", restored.tcpChainStepsList.single().marker)
        assertEquals(0.25, restored.evolutionEpsilon, 0.0)
        assertEquals("warp-profile", restored.warpProfileId)
        assertEquals(listOf("script-one", "script-two"), restored.relayAppsScriptScriptIdsList)
        assertTrue(restored.antiCorrelationEnabled)
    }

    @Test
    fun `secrets stay absent from export and cannot be injected during restore`() {
        val settings =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setBackupPin("private-pin")
                .setProxyLanAuthToken("private-lan-token")
                .build()
        val exported = appSettingsJson.parseToJsonElement(settings.toJson()).jsonObject
        assertFalse("backupPin" in exported)
        assertFalse("proxyLanAuthToken" in exported)

        val restored =
            appSettingsFromJson(
                """{"backupPin":"injected-pin","proxyLanAuthToken":"injected-token","proxyPort":2080}""",
            )
        assertEquals("", restored.backupPin)
        assertEquals("", restored.proxyLanAuthToken)
        assertEquals(2080, restored.proxyPort)
    }

    @Test
    fun `library numeric strings and absent defaults retain their declared meaning`() {
        val restored =
            appSettingsFromJson(
                """{"proxyPort":"2080","evolutionCooldownMs":"30000","futureField":{"x":true}}""",
            )
        assertEquals(2080, restored.proxyPort)
        assertEquals(30000L, restored.evolutionCooldownMs)
        assertEquals(AppSettingsSerializer.defaultValue.hapticFeedbackDisabled, restored.hapticFeedbackDisabled)
        assertEquals(AppSettingsSerializer.defaultValue.maxConnections, restored.maxConnections)
    }

    @Test
    fun `missing AWG wire profile cannot activate restored custom settings`() {
        assertThrows(IllegalArgumentException::class.java) {
            appSettingsFromJson("""{"warpAmneziaEnabled":true,"warpAmneziaPreset":"custom"}""")
        }
    }
}
