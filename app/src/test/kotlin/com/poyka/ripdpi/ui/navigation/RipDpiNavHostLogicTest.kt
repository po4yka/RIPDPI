package com.poyka.ripdpi.ui.navigation

import com.poyka.ripdpi.activities.HomeMode
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.screens.config.ConfigModeSection
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RipDpiNavHostLogicTest {
    @Test
    fun `top level routes stay in figma bottom bar order`() {
        assertEquals(
            listOf("home", "config", "diagnostics", "settings"),
            Route.topLevel.map(Route::stableRoute),
        )
    }

    @Test
    fun `top level route titles describe target information architecture`() {
        assertEquals(com.poyka.ripdpi.R.string.home, Route.Home.titleRes)
        assertEquals(com.poyka.ripdpi.R.string.config, Route.Config.titleRes)
        assertEquals(com.poyka.ripdpi.R.string.diagnostics, Route.Diagnostics().titleRes)
        assertEquals(com.poyka.ripdpi.R.string.settings, Route.Settings.titleRes)
    }

    @Test
    fun `top level route helper only matches bottom navigation destinations`() {
        assertTrue(Route.Home.stableRoute.isTopLevelRoute())
        assertTrue(Route.Settings.stableRoute.isTopLevelRoute())
        assertFalse(Route.History.stableRoute.isTopLevelRoute())
        assertFalse(null.isTopLevelRoute())
    }

    @Test
    fun `launch home request navigates away from top level routes`() {
        assertTrue(
            shouldNavigateToHomeFromLaunchRequest(
                launchHomeRequested = true,
                currentRoute = Route.Settings.stableRoute,
            ),
        )
    }

    @Test
    fun `launch home request is blocked during biometric gate`() {
        assertFalse(
            shouldNavigateToHomeFromLaunchRequest(
                launchHomeRequested = true,
                currentRoute = Route.BiometricPrompt.stableRoute,
            ),
        )
    }

    @Test
    fun `launch home request is ignored when already on home`() {
        assertFalse(
            shouldNavigateToHomeFromLaunchRequest(
                launchHomeRequested = true,
                currentRoute = Route.Home.stableRoute,
            ),
        )
    }

    @Test
    fun `history route stays off the bottom navigation`() {
        assertTrue(Route.all.contains(Route.History))
        assertFalse(Route.topLevel.contains(Route.History))
    }

    @Test
    fun `local bypass config route resolves without becoming a bottom tab`() {
        assertEquals(Route.LocalBypassConfig, Route.fromStableRoute(Route.LocalBypassConfig.stableRoute))
        assertTrue(Route.all.contains(Route.LocalBypassConfig))
        assertFalse(Route.topLevel.contains(Route.LocalBypassConfig))
    }

    @Test
    fun `vpn config route resolves without becoming a bottom tab`() {
        assertEquals(Route.VpnConfig, Route.fromStableRoute(Route.VpnConfig.stableRoute))
        assertTrue(Route.all.contains(Route.VpnConfig))
        assertFalse(Route.topLevel.contains(Route.VpnConfig))
    }

    @Test
    fun `diagnostics auto-start route keeps the stable bottom tab key`() {
        val autoStartRoute = Route.Diagnostics(autoStartScan = true)

        assertEquals("diagnostics", autoStartRoute.stableRoute)
        assertEquals(Route.Diagnostics(), Route.fromStableRoute(autoStartRoute.stableRoute))
        assertTrue(Route.topLevel.contains(Route.Diagnostics()))
    }

    @Test
    fun `logs route resolves as diagnose-owned utility destination`() {
        assertTrue(Route.all.contains(Route.Logs))
        assertFalse(Route.topLevel.contains(Route.Logs))
        assertEquals(Route.Logs, Route.fromStableRoute(Route.Logs.stableRoute))
    }

    @Test
    fun `owned stack browser route stays off the bottom navigation`() {
        assertTrue(Route.all.contains(Route.OwnedStackBrowser()))
        assertFalse(Route.topLevel.contains(Route.OwnedStackBrowser()))
    }

    @Test
    fun `route registry and stable matchers cover every NavHost destination`() {
        val navHostSource = File("src/main/kotlin/com/poyka/ripdpi/ui/navigation/RipDpiNavHost.kt").readText()
        val registeredDestinationTypes =
            Regex("""composable<Route\.([A-Za-z0-9_]+)>""")
                .findAll(navHostSource)
                .map { match -> match.groupValues[1] }
                .toList()
        val registeredRouteTypes = Route.all.map { route -> route::class.java.simpleName }
        val matcherPairs =
            Regex(
                """Route\.([A-Za-z0-9_]+)(?:\(\))?\.stableRoute to \{ hasRoute<Route\.([A-Za-z0-9_]+)>\(\) }""",
            ).findAll(
                navHostSource
                    .substringAfter("private val stableRouteMatchers")
                    .substringBefore("internal fun shouldNavigateToHomeFromLaunchRequest"),
            ).map { match -> match.groupValues[1] to match.groupValues[2] }
                .toList()

        assertEquals(
            "NavHost declares duplicate destination types",
            registeredDestinationTypes.size,
            registeredDestinationTypes.toSet().size,
        )
        assertEquals(
            "Route.all declares duplicate route types",
            registeredRouteTypes.size,
            registeredRouteTypes.toSet().size,
        )
        assertEquals(
            "Route.all declares duplicate stable routes",
            Route.all.size,
            Route.all
                .map(Route::stableRoute)
                .toSet()
                .size,
        )
        assertEquals("Stable route matcher count drifted", registeredDestinationTypes.size, matcherPairs.size)
        assertTrue(
            "Stable route matcher keys must match their typed destination: $matcherPairs",
            matcherPairs.all { (keyType, destinationType) -> keyType == destinationType },
        )
        assertEquals(
            "Route.all drifted from NavHost",
            registeredDestinationTypes.toSet(),
            registeredRouteTypes.toSet(),
        )
        assertEquals(
            "Stable route matchers drifted from NavHost",
            registeredDestinationTypes.toSet(),
            matcherPairs.map { it.second }.toSet(),
        )
    }

    @Test
    fun `outer scaffold insets are consumed before nested screen scaffolds`() {
        val navHostSource = File("src/main/kotlin/com/poyka/ripdpi/ui/navigation/RipDpiNavHost.kt").readText()
        val responsiveContentSource =
            navHostSource
                .substringAfter("private fun ResponsiveNavContent(")
                .substringBefore("private fun HandleLaunchRequests(")
        val navGraphSource = navHostSource.substringAfter("private fun RipDpiNavGraph(")

        assertTrue(
            "Wide navigation must consume outer padding before nested Scaffolds apply insets",
            Regex(
                """Row\(\s*modifier\s*=\s*Modifier\s*\.padding\(innerPadding\)\s*""" +
                    """\.consumeWindowInsets\(innerPadding\)""",
            ).containsMatchIn(responsiveContentSource),
        )
        assertTrue(
            "NavHost must consume the outer Scaffold padding so nested Scaffolds do not apply system insets twice",
            Regex(
                """modifier\s*=\s*Modifier\s*\.padding\(innerPadding\)\s*\.consumeWindowInsets\(innerPadding\)""",
            ).containsMatchIn(navGraphSource),
        )
    }

    @Test
    fun `diagnostic history app bars use Up without changing system Back`() {
        val navHostSource = File("src/main/kotlin/com/poyka/ripdpi/ui/navigation/RipDpiNavHost.kt").readText()
        val pcapRoute =
            navHostSource
                .substringAfter("composable<Route.PcapCaptureList>")
                .substringBefore("composable<Route.ReplayHistory>")
        val replayRoute =
            navHostSource
                .substringAfter("composable<Route.ReplayHistory>")
                .substringBefore("composable<Route.OwnedStackBrowser>")

        assertTrue(pcapRoute.contains("navController.navigateUp()"))
        assertTrue(replayRoute.contains("navController.navigateUp()"))
    }

    @Test
    fun `UI audit spec covers or explains every registered route`() {
        val auditSpec = File("../scripts/guide/specs/ui-ux-audit.yaml").readText()

        assertAuditRouteCoverage(auditSpec)
        assertAuditRequiredTags(auditSpec)
        assertAuditExpectedRoots(auditSpec)
        assertFalse(auditSpec.contains("strategy_import"))
        assertFalse(auditSpec.contains("profile_variants"))
    }

    private fun assertAuditRouteCoverage(auditSpec: String) {
        val pageRoutes =
            Regex("""(?m)^    route: "([^"]+)"$""")
                .findAll(auditSpec)
                .map { match -> match.groupValues[1] }
                .toSet()
        val exclusionMatches =
            Regex(
                """(?m)^    - route: "([^"]+)"\n      prerequisite: "([^"]+)"\n      reason: "([^"]+)"$""",
            ).findAll(auditSpec).toList()
        val excludedRoutes = exclusionMatches.map { match -> match.groupValues[1] }.toSet()
        val declaredExclusionRoutes =
            Regex("""(?m)^    - route: "([^"]+)"$""")
                .findAll(auditSpec)
                .map { match -> match.groupValues[1] }
                .toSet()
        val registeredRoutes = Route.all.map(Route::stableRoute).toSet()

        assertEquals("Every exclusion needs a prerequisite and reason", declaredExclusionRoutes, excludedRoutes)
        assertEquals(emptySet<String>(), pageRoutes intersect excludedRoutes)
        assertEquals("Audit route coverage drifted", registeredRoutes, pageRoutes + excludedRoutes)
    }

    private fun assertAuditRequiredTags(auditSpec: String) {
        val testTagsSource = File("src/main/kotlin/com/poyka/ripdpi/ui/testing/RipDpiTestTags.kt").readText()
        val staticTestTags =
            Regex("""const val [A-Za-z0-9_]+ = "([^"]+)"""")
                .findAll(testTagsSource)
                .associate { match ->
                    match.groupValues[1] to
                        match.value.substringAfter("const val ").substringBefore(" =")
                }
        val usedStaticTagNames =
            File("src/main/kotlin")
                .walkTopDown()
                .filter { file ->
                    file.isFile &&
                        file.extension == "kt" &&
                        file.name != "RipDpiTestTags.kt"
                }.flatMap { file ->
                    Regex("""RipDpiTestTags\.([A-Za-z0-9_]+)""")
                        .findAll(file.readText())
                        .map { match -> match.groupValues[1] }
                }.toSet()
        val usedStaticTestTags =
            staticTestTags
                .filterValues(usedStaticTagNames::contains)
                .keys
        val advancedSectionTags =
            File("src/main/kotlin")
                .walkTopDown()
                .filter { file -> file.isFile && file.extension == "kt" }
                .flatMap { file ->
                    Regex("""RipDpiTestTags\.advancedSection\("([^"]+)"\)""")
                        .findAll(file.readText())
                        .map { match -> RipDpiTestTags.advancedSection(match.groupValues[1]) }
                }.toSet()
        val generatedTestTags =
            Route.topLevel.map(RipDpiTestTags.bottomNav).toSet() +
                HomeMode.entries.flatMap { mode ->
                    listOf(
                        RipDpiTestTags.homeModeCard(mode.name),
                        RipDpiTestTags.homeModePrimaryAction(mode.name),
                    )
                } +
                Mode.entries.map { mode -> RipDpiTestTags.configMode(mode.name) } +
                ConfigModeSection.entries.map { section ->
                    RipDpiTestTags.configModeSection(section.stableKey)
                } +
                advancedSectionTags
        val requiredTestTags =
            Regex(
                """(?m)^    required_elements:(?: \[([^]]+)]|((?:\n      - "[^"]+")+))""",
            ).findAll(auditSpec)
                .flatMap { match ->
                    Regex(""""([^"]+)"""")
                        .findAll(match.groupValues.drop(1).joinToString("\n"))
                        .map { quoted -> quoted.groupValues[1] }
                }.toSet()

        assertEquals(
            "Audit spec references stale test tags",
            emptySet<String>(),
            requiredTestTags - usedStaticTestTags - generatedTestTags,
        )
    }

    private fun assertAuditExpectedRoots(auditSpec: String) {
        Regex("""(?ms)^  - id: .*?(?=^  - id: |\z)""").findAll(auditSpec).forEach { match ->
            val block = match.value
            val route = Regex("""(?m)^    route: "([^"]+)"$""").find(block)?.groupValues?.get(1)
            val expectedRoot = Regex("""(?m)^    expected_root: "([^"]+)"$""").find(block)?.groupValues?.get(1)
            if (route != null) {
                assertEquals("$route expected_root drifted", ExpectedRootScreenTags.getValue(route), expectedRoot)
            }
        }
    }

    @Test
    fun `every registered route has a reachable navigation mechanism and root tag`() {
        val registeredRoutes = Route.all.map(Route::stableRoute)
        val missingReachability = registeredRoutes.filterNot(ReachableRouteMechanisms::containsKey)
        val missingRootTags = registeredRoutes.filterNot(ExpectedRootScreenTags::containsKey)

        val logsMechanisms = ReachableRouteMechanisms.getValue(Route.Logs.stableRoute)

        assertEquals(emptyList<String>(), missingReachability)
        assertEquals(emptyList<String>(), missingRootTags)
        Route.all.forEach { route ->
            assertEquals("${route.stableRoute}-screen", ExpectedRootScreenTags.getValue(route.stableRoute))
            assertEquals(ExpectedRootScreenTags.getValue(route.stableRoute), RipDpiTestTags.screen(route))
        }
        assertTrue(logsMechanisms.contains(ReachabilityMechanism.ParentCallback))
    }

    @Test
    fun `removed diagnostic spec card routes stay out of runtime navigation`() {
        val removedRoutes =
            listOf(
                "handshake_timeline",
                "throughput_graph",
                "latency_graph",
                "state_machine",
                "oom_recovery",
                "strategy_ab",
                "strategy_import",
                "profile_variants",
                "replay_failure",
            )

        removedRoutes.forEach { stableRoute ->
            assertEquals(null, Route.fromStableRoute(stableRoute))
        }
    }
}

private enum class ReachabilityMechanism {
    StartDestination,
    TopLevelNavigation,
    InAppNavigate,
    ParentCallback,
    DeepLink,
    ImportIntent,
    LaunchRequest,
}

private val ExpectedRootScreenTags: Map<String, String> =
    mapOf(
        Route.Onboarding.stableRoute to "onboarding-screen",
        Route.Home.stableRoute to "home-screen",
        Route.Config.stableRoute to "config-screen",
        Route.LocalBypassConfig.stableRoute to "config/local_bypass-screen",
        Route.VpnConfig.stableRoute to "config/vpn-screen",
        Route.Settings.stableRoute to "settings-screen",
        Route.BackupRestore.stableRoute to "backup_restore-screen",
        Route.Diagnostics().stableRoute to "diagnostics-screen",
        Route.History.stableRoute to "history-screen",
        Route.Logs.stableRoute to "logs-screen",
        Route.ConnectionHealth.stableRoute to "connection_health-screen",
        Route.SubscriptionFailover.stableRoute to "subscription_failover-screen",
        Route.SubscriptionStatus.stableRoute to "subscription_status-screen",
        Route.StrategyTuner.stableRoute to "strategy_tuner-screen",
        Route.ModeEditor.stableRoute to "mode_editor-screen",
        Route.DnsSettings.stableRoute to "dns_settings-screen",
        Route.AdvancedSettings.stableRoute to "advanced_settings-screen",
        Route.StrategyConfig.stableRoute to "strategy_config-screen",
        Route.RememberedNetworks.stableRoute to "remembered_networks-screen",
        Route.RootModeStrategies.stableRoute to "root_mode_strategies-screen",
        Route.DomainBypassList.stableRoute to "domain_bypass_list-screen",
        Route.AssetProvider.stableRoute to "asset_provider-screen",
        Route.SplitTunnel.stableRoute to "split_tunnel-screen",
        Route.Routes.stableRoute to "routes-screen",
        Route.RuleEditor().stableRoute to "rule_editor-screen",
        Route.Blockcheck.stableRoute to "blockcheck-screen",
        Route.BiometricPrompt.stableRoute to "biometric_prompt-screen",
        Route.AppCustomization.stableRoute to "app_customization-screen",
        Route.About.stableRoute to "about-screen",
        Route.DataTransparency.stableRoute to "data_transparency-screen",
        Route.DetectionCheck.stableRoute to "detection_check-screen",
        Route.DetectionSettings.stableRoute to "detection_settings-screen",
        Route.PcapViewer.stableRoute to "pcap_viewer-screen",
        Route.PcapCaptureList.stableRoute to "pcap_capture_list-screen",
        Route.ReplayHistory.stableRoute to "replay_history-screen",
        Route.SharedDiagnosticResult().stableRoute to "shared_diagnostic_result-screen",
        Route.OwnedStackBrowser().stableRoute to "owned_stack_browser-screen",
        Route.ProfileImportConfirm().stableRoute to "import/profile_confirm-screen",
        Route.SubscriptionImportConfirm().stableRoute to "import/subscription_confirm-screen",
        Route.SupportSettings().stableRoute to "support/settings-screen",
        Route.ProfileShare().stableRoute to "profile/share-screen",
        Route.QrScanner.stableRoute to "scanner-screen",
        Route.AmneziaWgProfile.stableRoute to "profile/amneziawg-screen",
        Route.XrayImport.stableRoute to "xray/import-screen",
        Route.AnyTlsProfile.stableRoute to "profile/anytls-screen",
        Route.MieruProfile.stableRoute to "profile/mieru-screen",
        Route.SshProfile.stableRoute to "profile/ssh-screen",
    )

private val ReachableRouteMechanisms: Map<String, Set<ReachabilityMechanism>> =
    mapOf(
        Route.Onboarding.stableRoute to setOf(ReachabilityMechanism.StartDestination),
        Route.Home.stableRoute to
            setOf(
                ReachabilityMechanism.StartDestination,
                ReachabilityMechanism.TopLevelNavigation,
                ReachabilityMechanism.DeepLink,
            ),
        Route.Config.stableRoute to
            setOf(
                ReachabilityMechanism.TopLevelNavigation,
                ReachabilityMechanism.DeepLink,
            ),
        Route.LocalBypassConfig.stableRoute to setOf(ReachabilityMechanism.ParentCallback),
        Route.VpnConfig.stableRoute to setOf(ReachabilityMechanism.ParentCallback),
        Route.Settings.stableRoute to
            setOf(
                ReachabilityMechanism.TopLevelNavigation,
                ReachabilityMechanism.DeepLink,
            ),
        Route.BackupRestore.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.Diagnostics().stableRoute to
            setOf(
                ReachabilityMechanism.TopLevelNavigation,
                ReachabilityMechanism.InAppNavigate,
                ReachabilityMechanism.DeepLink,
            ),
        Route.History.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.Logs.stableRoute to setOf(ReachabilityMechanism.ParentCallback),
        Route.ConnectionHealth.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.SubscriptionFailover.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.SubscriptionStatus.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.StrategyTuner.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.ModeEditor.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.DnsSettings.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.AdvancedSettings.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.StrategyConfig.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.RememberedNetworks.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.RootModeStrategies.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.DomainBypassList.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.AssetProvider.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.SplitTunnel.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.Routes.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.RuleEditor().stableRoute to setOf(ReachabilityMechanism.ParentCallback),
        Route.Blockcheck.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.BiometricPrompt.stableRoute to
            setOf(
                ReachabilityMechanism.StartDestination,
                ReachabilityMechanism.LaunchRequest,
            ),
        Route.AppCustomization.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.About.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.DataTransparency.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.DetectionCheck.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.DetectionSettings.stableRoute to setOf(ReachabilityMechanism.ParentCallback),
        Route.PcapViewer.stableRoute to setOf(ReachabilityMechanism.ParentCallback),
        Route.PcapCaptureList.stableRoute to setOf(ReachabilityMechanism.ParentCallback),
        Route.ReplayHistory.stableRoute to setOf(ReachabilityMechanism.ParentCallback),
        Route.SharedDiagnosticResult().stableRoute to setOf(ReachabilityMechanism.LaunchRequest),
        Route.OwnedStackBrowser().stableRoute to setOf(ReachabilityMechanism.ParentCallback),
        Route.ProfileImportConfirm().stableRoute to
            setOf(
                ReachabilityMechanism.ParentCallback,
                ReachabilityMechanism.ImportIntent,
            ),
        Route.SubscriptionImportConfirm().stableRoute to setOf(ReachabilityMechanism.ImportIntent),
        Route.SupportSettings().stableRoute to setOf(ReachabilityMechanism.ImportIntent),
        Route.ProfileShare().stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.QrScanner.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.AmneziaWgProfile.stableRoute to setOf(ReachabilityMechanism.LaunchRequest),
        Route.XrayImport.stableRoute to
            setOf(
                ReachabilityMechanism.InAppNavigate,
                ReachabilityMechanism.LaunchRequest,
            ),
        Route.AnyTlsProfile.stableRoute to setOf(ReachabilityMechanism.LaunchRequest),
        Route.MieruProfile.stableRoute to setOf(ReachabilityMechanism.LaunchRequest),
        Route.SshProfile.stableRoute to setOf(ReachabilityMechanism.LaunchRequest),
    )
