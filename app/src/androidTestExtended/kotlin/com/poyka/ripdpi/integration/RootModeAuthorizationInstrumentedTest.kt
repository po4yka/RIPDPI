package com.poyka.ripdpi.integration

import android.net.VpnService
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.activities.MainActivity
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.automation.AutomationServicePreset
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.services.RootAccessResult
import com.poyka.ripdpi.services.RootHelperManager
import com.poyka.ripdpi.services.RootHelperState
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceRuntimeRegistry
import com.poyka.ripdpi.services.ServiceStartResult
import com.poyka.ripdpi.ui.navigation.RootModeStrategies
import com.poyka.ripdpi.ui.screens.settings.RootModeTestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Use an isolated debug installation. Pass `-e ripdpi.rootAuthorization` to Android instrumentation.
 *
 * `granted`: Allow the app in the root manager.
 * `denied-then-granted`: Refuse the first check without saving the refusal, then allow the retry.
 * `unavailable`: Use a device where no superuser command can run.
 * `granted-live-vpn`: Grant Android VPN consent before the test and allow root for its duration.
 */
@HiltAndroidTest
class RootModeAuthorizationInstrumentedTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule(ComposeUiTestConfig())

    @Inject lateinit var settings: AppSettingsRepository

    @Inject lateinit var rootHelper: RootHelperManager

    @Inject lateinit var serviceController: ServiceController

    @Inject lateinit var serviceState: ServiceStateStore

    @Inject lateinit var runtimeRegistry: ServiceRuntimeRegistry

    @Test
    fun rootAuthorizationFollowsTheExplicitChoice() {
        val run = InstrumentationRegistry.getArguments().getString("ripdpi.rootAuthorization")
        assumeTrue("Pass ripdpi.rootAuthorization on an isolated debug installation", run != null)
        require(BuildConfig.DEBUG) { "Root authorization acceptance requires a debug build" }
        require(run in setOf("granted", "denied-then-granted", "unavailable", "granted-live-vpn")) {
            "Unknown ripdpi.rootAuthorization outcome"
        }
        hiltRule.inject()

        assertEquals(AppStatus.Halted, serviceState.status.value.first)
        assertNull(rootHelper.socketPath)
        val originalSettings = runBlocking { settings.snapshot() }
        try {
            ActivityScenario
                .launch<MainActivity>(
                    automationLaunchIntent(
                        startRoute = RootModeStrategies.stableRoute,
                        resetState = false,
                        servicePreset = AutomationServicePreset.Live,
                        dataPreset = AutomationDataPreset.SettingsReady,
                    ),
                ).use { activity ->
                    waitForTag(RootModeTestTags.Toggle)
                    composeRule.onNodeWithTag(RootModeTestTags.Toggle).assertIsOff()
                    assertNull("Opening settings must not request root", rootHelper.lastAccessResult.value)
                    composeRule.onNodeWithTag(RootModeTestTags.Toggle).performClick()

                    when (run) {
                        "denied-then-granted" -> {
                            awaitAccess(RootAccessResult.Denied)
                            composeRule.onNodeWithTag(RootModeTestTags.Toggle).assertIsOff()
                            assertFalse(runBlocking { settings.snapshot().rootModeAuthorized })
                            waitForTag(RootModeTestTags.Retry)
                            composeRule.onNodeWithTag(RootModeTestTags.Retry).performClick()
                        }

                        "unavailable" -> {
                            awaitAccess(RootAccessResult.Unavailable)
                            composeRule.onNodeWithTag(RootModeTestTags.Toggle).assertIsOff()
                            assertFalse(runBlocking { settings.snapshot().rootModeAuthorized })
                            waitForTag(RootModeTestTags.Retry)
                            return@use
                        }
                    }

                    awaitAccess(
                        RootAccessResult.Granted,
                        previous = RootAccessResult.Denied.takeIf { run == "denied-then-granted" },
                    )
                    composeRule.onNodeWithTag(RootModeTestTags.Toggle).assertIsOn()
                    assertTrue(runBlocking { settings.snapshot().rootModeAuthorized })
                    assertNull("Authorization alone must not start the helper", rootHelper.socketPath)

                    if (run == "granted-live-vpn") {
                        activity.onActivity {
                            assertNull("Grant Android VPN consent before this test", VpnService.prepare(it))
                        }
                        disableDuringVpn()
                    } else {
                        disableRoot()
                    }
                }
        } finally {
            runBlocking { settings.replace(originalSettings) }
            rootHelper.recordAccessResult(null)
        }
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = UiTimeoutMillis) {
            composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitAccess(
        expected: RootAccessResult,
        previous: RootAccessResult? = null,
    ) {
        val result =
            runBlocking {
                withTimeout(AccessTimeoutMillis) {
                    rootHelper.lastAccessResult.first { it != null && it != previous }
                }
            }
        assertEquals(expected, result)
        composeRule.waitForIdle()
    }

    private fun disableRoot() {
        composeRule.onNodeWithTag(RootModeTestTags.Toggle).performClick()
        runBlocking { withTimeout(UiTimeoutMillis) { settings.settings.first { !it.rootModeAuthorized } } }
        composeRule.onNodeWithTag(RootModeTestTags.Toggle).assertIsOff()
    }

    private fun disableDuringVpn() {
        runBlocking {
            settings.update {
                setStrategyChains(
                    listOf(
                        TcpChainStepModel(TcpChainStepKind.Split, "host"),
                        TcpChainStepModel(TcpChainStepKind.FakeRst, "host"),
                    ),
                    emptyList(),
                )
            }
        }
        try {
            val start = runBlocking { serviceController.start(Mode.VPN) }
            assertTrue("VPN start must be accepted: $start", start is ServiceStartResult.Accepted)
            composeRule.waitUntil(timeoutMillis = AccessTimeoutMillis) {
                serviceState.status.value == (AppStatus.Running to Mode.VPN) &&
                    rootHelper.state.value == RootHelperState.Ready
            }
            assertNotNull("A privileged strategy must have a live helper socket", rootHelper.socketPath)
            val runningSession = checkNotNull(runtimeRegistry.current(Mode.VPN))
            val appliedPolicy = checkNotNull(runningSession.activeConnectionPolicy.value)

            disableRoot()
            composeRule.waitUntil(timeoutMillis = AccessTimeoutMillis) {
                rootHelper.state.value == RootHelperState.Stopped &&
                    runningSession.activeConnectionPolicy.value?.let { it != appliedPolicy } == true
            }
            assertNull(rootHelper.socketPath)
            assertFalse(rootHelper.isRunning())
            assertEquals(AppStatus.Running to Mode.VPN, serviceState.status.value)
            assertEquals(runningSession.runtimeId, runtimeRegistry.current(Mode.VPN)?.runtimeId)
        } finally {
            runBlocking {
                serviceController.stop()
                withTimeout(AccessTimeoutMillis) {
                    serviceState.status.first { (status, _) -> status == AppStatus.Halted }
                }
            }
        }
    }

    private companion object {
        const val UiTimeoutMillis = 10_000L
        const val AccessTimeoutMillis = 35_000L
    }
}
