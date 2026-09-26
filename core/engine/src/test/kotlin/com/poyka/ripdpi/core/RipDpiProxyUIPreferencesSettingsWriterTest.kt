package com.poyka.ripdpi.core

import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertTrue
import org.junit.Test

class RipDpiProxyUIPreferencesSettingsWriterTest {
    @Test
    fun `parser evasions survive applying preferences to settings`() {
        val preferences =
            RipDpiProxyUIPreferences(
                parserEvasions = RipDpiParserEvasionConfig(httpHostExtraSpace = true, httpHostTab = true),
            )

        val updated = preferences.applyToSettings(AppSettings.getDefaultInstance())

        assertTrue(updated.httpHostExtraSpace)
        assertTrue(updated.httpHostTab)
    }
}
