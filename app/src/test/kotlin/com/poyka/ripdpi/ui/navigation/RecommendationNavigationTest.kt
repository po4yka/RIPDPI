package com.poyka.ripdpi.ui.navigation

import com.poyka.ripdpi.core.detection.RecommendationDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class RecommendationNavigationTest {
    @Test
    fun `recommendation destinations open their existing settings owners`() {
        val expected =
            mapOf(
                RecommendationDestination.MODE_SETTINGS to Route.ModeEditor,
                RecommendationDestination.PROXY_SETTINGS to Route.LocalBypassConfig,
                RecommendationDestination.DNS_SETTINGS to Route.DnsSettings,
                RecommendationDestination.GENERAL_SETTINGS to Route.Settings,
                RecommendationDestination.ADVANCED_SETTINGS to Route.AdvancedSettings,
            )

        assertEquals(RecommendationDestination.entries.toSet(), expected.keys)
        expected.forEach { (destination, route) -> assertEquals(route, destination.toRoute()) }
    }
}
