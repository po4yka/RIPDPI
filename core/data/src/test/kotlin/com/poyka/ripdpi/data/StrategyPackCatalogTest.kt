package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyPackCatalogTest {
    @Test
    fun `compatibility check accepts matching app and native versions`() {
        val catalog =
            StrategyPackCatalog(
                minAppVersion = "0.0.4",
                minNativeVersion = "0.1.0",
            )

        val compatibility = catalog.checkCompatibility(appVersion = "0.0.4", nativeVersion = "0.1.0")

        assertTrue(compatibility.isCompatible)
        assertEquals(null, compatibility.reason)
    }

    @Test
    fun `compatibility check rejects older app versions`() {
        val catalog =
            StrategyPackCatalog(
                minAppVersion = "0.0.5",
                minNativeVersion = "0.1.0",
            )

        val compatibility = catalog.checkCompatibility(appVersion = "0.0.4", nativeVersion = "0.1.0")

        assertFalse(compatibility.isCompatible)
        assertEquals("Requires app version 0.0.5 or newer", compatibility.reason)
    }

    @Test
    fun `version comparison ignores debug suffixes through segment parsing`() {
        assertTrue(compareVersionStrings("0.1.0-debug", "0.1.0") == 0)
        assertTrue(compareVersionStrings("1.2.0", "1.1.9") > 0)
        assertTrue(compareVersionStrings("0.0.9", "0.1.0") < 0)
    }

    @Test
    fun `normalizes strategy pack settings values`() {
        assertEquals(StrategyPackChannelStable, normalizeStrategyPackChannel(" stable "))
        assertEquals(StrategyPackChannelBeta, normalizeStrategyPackChannel("BETA"))
        assertEquals(StrategyPackRefreshPolicyManual, normalizeStrategyPackRefreshPolicy("manual"))
        assertEquals(StrategyPackRefreshPolicyAutomatic, normalizeStrategyPackRefreshPolicy("unknown"))
    }
}
