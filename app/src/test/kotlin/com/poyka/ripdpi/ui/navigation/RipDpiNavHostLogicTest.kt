package com.poyka.ripdpi.ui.navigation

import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `every registered route has a reachable navigation mechanism`() {
        val missing = Route.all.map(Route::stableRoute).filterNot(ReachableRouteMechanisms::containsKey)

        val logsMechanisms = ReachableRouteMechanisms.getValue(Route.Logs.stableRoute)

        assertEquals(emptyList<String>(), missing)
        assertTrue(logsMechanisms.contains(ReachabilityMechanism.ParentCallback))
    }

    @Test
    fun `every registered route has the canonical screen root tag`() {
        val expectedRootTags = Route.all.associate { route -> route.stableRoute to "${route.stableRoute}-screen" }

        assertEquals(
            expectedRootTags,
            Route.all.associate { route -> route.stableRoute to RipDpiTestTags.screen(route) },
        )
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
        Route.ModeEditor.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.DnsSettings.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.AdvancedSettings.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.StrategyConfig.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.DomainBypassList.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.AssetProvider.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.SplitTunnel.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.Routes.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.RuleEditor().stableRoute to setOf(ReachabilityMechanism.ParentCallback),
        Route.Blockcheck.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.ProfilePreflight.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.BiometricPrompt.stableRoute to
            setOf(
                ReachabilityMechanism.StartDestination,
                ReachabilityMechanism.LaunchRequest,
            ),
        Route.AppCustomization.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.About.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.DataTransparency.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.PrivacyThreatModel.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
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
        Route.QrScanner.stableRoute to setOf(ReachabilityMechanism.InAppNavigate),
        Route.AmneziaWgProfile.stableRoute to setOf(ReachabilityMechanism.LaunchRequest),
        Route.XrayImport.stableRoute to setOf(ReachabilityMechanism.LaunchRequest),
        Route.AnyTlsProfile.stableRoute to setOf(ReachabilityMechanism.LaunchRequest),
        Route.MieruProfile.stableRoute to setOf(ReachabilityMechanism.LaunchRequest),
        Route.SshProfile.stableRoute to setOf(ReachabilityMechanism.LaunchRequest),
    )
