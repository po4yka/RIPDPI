package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.awg.requireRuntimeReady
import com.poyka.ripdpi.data.subscription.SingBoxParseResult
import com.poyka.ripdpi.data.subscription.SingBoxSubscriptionParser
import com.poyka.ripdpi.data.subscription.toActivationRequest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Locks the committed CI fixture bundle
 * (`scripts/fixtures/embedded-relay-bundle/ci-fixture.json`) against the parse
 * and activation contracts the Simple-flavor [ConfigSeeder] enforces at
 * startup.
 *
 * Dependabot pull requests run without Actions secrets, so the CI setup action
 * materializes this committed fixture instead of the secret bundle. A fixture
 * that stops satisfying the seeder contract would crash the Simple app during
 * every secret-less instrumented run; this unit test fails long before that.
 */
class CiRelayBundleFixtureTest {
    private fun fixtureText(): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
        }
        val root = requireNotNull(dir) { "repository root not found" }
        val fixture = File(root, "scripts/fixtures/embedded-relay-bundle/ci-fixture.json")
        require(fixture.isFile) { "missing CI fixture bundle: $fixture" }
        return fixture.readText()
    }

    private fun parseFixture(): SingBoxParseResult.Success {
        val result = SingBoxSubscriptionParser.parse(fixtureText(), "ci-fixture-group")
        return result as? SingBoxParseResult.Success
            ?: error("CI fixture bundle failed to parse: $result")
    }

    @Test
    fun `fixture parses with the profile mix the simple seeder requires`() {
        val success = parseFixture()
        assertTrue("fixture must not skip outbounds: ${success.skipped}", success.skipped.isEmpty())
        assertTrue(
            "fixture needs a VLESS+Reality primary",
            success.profiles.any { it is ProxyProfile.VlessReality },
        )
        assertTrue(
            "fixture needs a TCP-diverse VLESS/xHTTP reserve",
            success.profiles.any { it is ProxyProfile.Vless && it.xhttpPath != null },
        )
        assertTrue(
            "fixture needs a Hysteria2 reserve",
            success.profiles.any { it is ProxyProfile.Hysteria2 },
        )
        assertTrue("fixture needs an AWG reserve", success.amneziaWgProfiles.isNotEmpty())
    }

    @Test
    fun `fixture profiles pass native relay validation and AWG runtime readiness`() {
        val success = parseFixture()
        success.profiles.forEach { profile ->
            assertTrue(
                "fixture profile ${profile.displayName} fails native relay validation",
                validateNativeRelayProfile(profile),
            )
        }
        success.amneziaWgProfiles
            .map { it.toActivationRequest() }
            .forEach { request -> request.requireRuntimeReady() }
    }
}
