package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultHomeAnalysisAugmentationSourceTest {
    @Test
    fun `homeNetworkIdentitySignal uses coarse cellular operator code`() {
        assertEquals(
            "Carrier 25099",
            homeNetworkIdentitySignal(
                transport = "cellular",
                cellularOperatorCode = "25099",
            ),
        )
    }

    @Test
    fun `homeNetworkIdentitySignal drops wifi identity rather than scraping SSID`() {
        assertNull(homeNetworkIdentitySignal(transport = "wifi"))
    }
}
