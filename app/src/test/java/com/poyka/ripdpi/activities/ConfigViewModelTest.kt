package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigViewModelTest {
    private val defaultDraft = AppSettingsSerializer.defaultValue.toConfigDraft()

    @Test
    fun `default draft marks recommended preset as selected`() {
        val presets = buildConfigPresets(defaultDraft)

        assertTrue(presets.first { it.id == "recommended" }.isSelected)
        assertFalse(presets.first { it.id == "proxy" }.isSelected)
        assertFalse(presets.first { it.id == "custom" }.isSelected)
    }

    @Test
    fun `proxy draft marks proxy preset as selected`() {
        val presets = buildConfigPresets(defaultDraft.copy(mode = Mode.Proxy))

        assertFalse(presets.first { it.id == "recommended" }.isSelected)
        assertTrue(presets.first { it.id == "proxy" }.isSelected)
        assertFalse(presets.first { it.id == "custom" }.isSelected)
    }

    @Test
    fun `invalid draft reports validation errors`() {
        val errors =
            validateConfigDraft(
                defaultDraft.copy(
                    proxyPort = "0",
                    maxConnections = "0",
                    bufferSize = "0",
                    defaultTtl = "999",
                ),
            )

        assertEquals("invalid_port", errors[ConfigFieldProxyPort])
        assertEquals("out_of_range", errors[ConfigFieldMaxConnections])
        assertEquals("out_of_range", errors[ConfigFieldBufferSize])
        assertEquals("out_of_range", errors[ConfigFieldDefaultTtl])
    }
}
