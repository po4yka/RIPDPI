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

    @Test
    fun `resolves typed pack sections from pinned selection`() {
        val catalog =
            StrategyPackCatalog(
                schemaVersion = StrategyPackCatalogSchemaVersion,
                packs =
                    listOf(
                        StrategyPackDefinition(
                            id = "mobile",
                            version = "2026.04.1",
                            title = "Mobile",
                            description = "Mobile defaults",
                            hostListRefs = listOf("video"),
                            tlsProfileSetId = "baseline",
                            morphPolicyId = "balanced",
                            transportModuleIds = listOf("snowflake"),
                            featureFlagIds =
                                listOf(
                                    StrategyFeatureCloudflareConsumeValidation,
                                    StrategyFeatureNaiveProxyWatchdog,
                                ),
                        ),
                    ),
                hostLists = listOf(StrategyPackHostList(id = "video", title = "Video")),
                tlsProfiles =
                    listOf(
                        StrategyPackTlsProfileSet(
                            id = "baseline",
                            title = "Baseline",
                            allowedProfileIds = listOf("chrome_stable", "firefox_stable"),
                            rotationEnabled = true,
                            browserFamilies = listOf("chrome", "firefox"),
                            echPolicy = "opportunistic",
                            proxyModeNotice = "browser_native_tls_suppressed",
                            acceptanceCorpusRef = "phase11_tls_template_acceptance",
                            acceptanceReportRef = "tls_template_acceptance_report",
                            reviewedAt = "2026-04-18",
                        ),
                    ),
                morphPolicies = listOf(StrategyPackMorphPolicy(id = "balanced", title = "Balanced")),
                transportModules =
                    listOf(
                        StrategyPackTransportModule(
                            id = "snowflake",
                            kind = "snowflake",
                            title = "Snowflake",
                        ),
                    ),
                featureFlags =
                    listOf(
                        StrategyPackFeatureFlag(id = StrategyFeatureCloudflareConsumeValidation, enabled = true),
                        StrategyPackFeatureFlag(id = StrategyFeatureNaiveProxyWatchdog, enabled = true),
                        StrategyPackFeatureFlag(id = StrategyFeatureCloudflarePublish, enabled = false),
                    ),
            )

        val selection = catalog.resolveSelection(pinnedPackId = "mobile", pinnedPackVersion = "2026.04.1")

        assertEquals("mobile", selection.pack?.id)
        assertEquals("baseline", selection.tlsProfileSet?.id)
        assertEquals(listOf("chrome", "firefox"), selection.tlsProfileSet?.browserFamilies)
        assertEquals("opportunistic", selection.tlsProfileSet?.echPolicy)
        assertEquals("browser_native_tls_suppressed", selection.tlsProfileSet?.proxyModeNotice)
        assertEquals("phase11_tls_template_acceptance", selection.tlsProfileSet?.acceptanceCorpusRef)
        assertEquals("tls_template_acceptance_report", selection.tlsProfileSet?.acceptanceReportRef)
        assertEquals("2026-04-18", selection.tlsProfileSet?.reviewedAt)
        assertEquals("balanced", selection.morphPolicy?.id)
        assertEquals(listOf("video"), selection.hostLists.map { it.id })
        assertEquals(listOf("snowflake"), selection.transportModules.map { it.id })
        assertEquals(
            listOf(StrategyFeatureCloudflareConsumeValidation, StrategyFeatureNaiveProxyWatchdog),
            selection.featureFlags.map { it.id },
        )
    }
}
