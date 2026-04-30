package com.poyka.ripdpi.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedPriorsReleaseConfigTest {
    @Test
    fun `release config is disabled when manifest URL is missing`() {
        val config =
            SharedPriorsReleaseConfig(
                manifestUrl = "",
                priorsUrl = "https://example.com/shared-priors.bin",
            )

        assertFalse(config.isConfigured)
    }

    @Test
    fun `release config is disabled when priors URL is missing`() {
        val config =
            SharedPriorsReleaseConfig(
                manifestUrl = "https://example.com/shared-priors.json",
                priorsUrl = "",
            )

        assertFalse(config.isConfigured)
    }

    @Test
    fun `release config is enabled when both URLs are present`() {
        val config =
            SharedPriorsReleaseConfig(
                manifestUrl = "https://example.com/shared-priors.json",
                priorsUrl = "https://example.com/shared-priors.bin",
            )

        assertTrue(config.isConfigured)
    }
}
