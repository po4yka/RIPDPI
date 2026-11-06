package com.poyka.ripdpi.data

import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class AppSettingsJsonInvalidValueTest(
    private val payload: String,
) {
    @Test
    fun `invalid known value is rejected instead of restored as a default`() {
        assertThrows(IllegalArgumentException::class.java) {
            appSettingsFromJson(payload)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun payloads(): List<Array<String>> =
            listOf(
                """{"formatVersion":"future"}""",
                """{"formatVersion":null}""",
                """{"proxyPort":"invalid"}""",
                """{"proxyPort":1080.5}""",
                """{"proxyPort":2147483648}""",
                """{"relayEnabled":"yes"}""",
                """{"hapticFeedbackDisabled":null}""",
                """{"appTheme":123}""",
                """{"appTheme":null}""",
                """{"evolutionEpsilon":"invalid"}""",
                """{"evolutionCooldownMs":"invalid"}""",
                """{"relayAppsScriptScriptIds":[null]}""",
                """{"relayAppsScriptScriptIds":[123]}""",
                """{"relayChainMiddleProfileIds":null}""",
                """{"groupActivationFilter":[]}""",
                """{"tcpChainSteps":[{"kind":"split","marker":"1","activationFilter":[]}]}""",
                """{"mode":"VPN"}""",
                """{"mode":"invalid"}""",
                """{"warpAmneziaJc":1}""",
                """{"warpAmneziaWireProfile":"awg-3.1-ranges-v1","warpAmneziaJc":1}""",
            ).map { arrayOf(it) }
    }
}
