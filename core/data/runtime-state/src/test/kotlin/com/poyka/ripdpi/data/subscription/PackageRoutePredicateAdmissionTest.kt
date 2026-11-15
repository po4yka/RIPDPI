package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.routing.PackageRoutingAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageRoutePredicateAdmissionTest {
    @Test
    fun `conditional and non-route package rules reject the bundle`() {
        listOf(
            "\"invert\":true",
            "\"domain\":[\"example.org\"]",
            "\"network\":\"udp\"",
            "\"port\":[443]",
            "\"type\":\"logical\"",
            "\"action\":\"reject\"",
            "\"invert\":\"false\"",
            "\"rules\":[]",
        ).forEach { condition ->
            val result = SingBoxSubscriptionParser.parse(bundle(condition), "group")
            assertTrue(condition, result is SingBoxParseResult.Error)
            assertTrue(condition, (result as SingBoxParseResult.Error).recognizedBundle)
        }
    }

    @Test
    fun `explicit default route and false inversion retain whole-app meaning`() {
        val result =
            SingBoxSubscriptionParser.parse(
                bundle("\"type\":\"default\",\"action\":\"route\",\"invert\":false"),
                "group",
            ) as SingBoxParseResult.Success

        assertEquals(PackageRoutingAction.BYPASS, result.packageRoutingRules.single().action)
        assertEquals("com.example.app", result.packageRoutingRules.single().packageName)
    }

    @Test
    fun `an inverted empty package match is not ignored`() {
        val result =
            SingBoxSubscriptionParser.parse(
                bundle("\"invert\":true").replace("[\"com.example.app\"]", "[]"),
                "group",
            )

        assertTrue(result is SingBoxParseResult.Error)
    }

    private fun bundle(condition: String): String =
        """{"outbounds":[{"type":"direct","tag":"direct"}],
            "route":{"rules":[{"package_name":["com.example.app"],"outbound":"direct",$condition}]}}"""
}
