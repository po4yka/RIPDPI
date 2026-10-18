package com.poyka.ripdpi.e2e

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.MainActivity
import com.poyka.ripdpi.automation.AutomationLaunchContract
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.AppliedRuntimeConfigurationSource
import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.data.LocalNetworkPermission
import com.poyka.ripdpi.data.LocalNetworkPermissionApi
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.RuntimeConfigurationPendingStatus
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.DefaultServiceController
import com.poyka.ripdpi.services.RunningServiceReconnect
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceStartResult
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import java.io.File
import javax.inject.Inject

/**
 * Real Home/service/native/network acceptance. All UI actions come from the leased external driver.
 * The shared test application replaces startup readiness and public-IP lookup only; it is not a
 * production Application-startup or public-IP acceptance test.
 */
@HiltAndroidTest
class HomeReconnectNetworkAcceptanceTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val testName = TestName()

    @Inject lateinit var serviceController: ServiceController

    @Inject lateinit var settings: AppSettingsRepository

    @Inject lateinit var serviceState: ServiceStateStore

    @Inject lateinit var applications: AppliedRuntimeConfigurationSource

    @Inject lateinit var reconnect: RunningServiceReconnect

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val observers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var ownership: HomeReconnectOwnershipJournal
    private lateinit var ownedSettings: List<AppSettings>
    private lateinit var replacementDnsProtocol: String
    private lateinit var initialReceipt: com.poyka.ripdpi.data.RuntimeActivationReceipt
    private var baselineIds = emptySet<String>()
    private lateinit var fixtureClient: LocalFixtureClient
    private lateinit var fixture: FixtureManifestDto
    private lateinit var evidence: HomeReconnectNetworkAcceptanceSupport
    private lateinit var ui: HomeReconnectTalkBackSupport
    private var activity: ActivityScenario<MainActivity>? = null
    private var ownsService = false

    @Before
    fun setUp() {
        requireExternalReconnectDriver()
        check(isE2eFixtureConfigured()) {
            "Actual reconnect acceptance requires fixtureControlHost/fixtureControlPort; no skipped PASS"
        }
        val runId = checkNotNull(arguments.getString("ripdpi.acceptanceRunId")) { "Unique acceptanceRunId is required" }
        check(runId.matches(Regex("[A-Za-z0-9-]{1,80}")))
        val expectedOff = arguments.getString("ripdpi.reconnectAnimationsOff")
        check(expectedOff in listOf("true", "false")) { "Explicit actual motion policy is required" }
        assertEquals(expectedOff != "true", ValueAnimator.areAnimatorsEnabled())
        hilt.inject()
        assertTrue("No fake service controller", serviceController is DefaultServiceController)
        assertTrue("No injected connected state", serviceState is DefaultServiceStateStore)
        assertEquals(
            "Only a stopped disposable service can be owned by this test",
            AppStatus.Halted,
            serviceState.status.value.first,
        )
        val originalSettings = runBlocking { settings.snapshot() }
        baselineIds =
            applications.applications.value.values
                .filterIsInstance<RuntimeConfigurationApplication.Applied>()
                .map { it.configuration.runtimeId }
                .toSet()
        requireNetworkPermissions(context.packageName)
        requireNetworkPermissions(instrumentation.context.packageName)
        fixtureClient = LocalFixtureClient.fromInstrumentationArgs()
        fixture = fixtureClient.manifest()
        assertEquals(
            "Fixture DNS must resolve back to the local host fixture",
            fixture.androidHost,
            fixture.dnsAnswerIpv4,
        )
        val directory = File(context.filesDir, "home-reconnect-acceptance/$runId/${testName.methodName}")
        ownership = HomeReconnectOwnershipJournal(context, directory)
        ownership.prepare(originalSettings)
        evidence =
            HomeReconnectNetworkAcceptanceSupport(instrumentation, fixtureClient, fixture, serviceState, directory)
        ui =
            HomeReconnectTalkBackSupport(
                instrumentation,
                directory,
                arguments.getString("ripdpi.reconnectTalkBack") == "true",
            )
        evidence.record(
            "bindings",
            JSONObject()
                .put("serviceController", serviceController.javaClass.name)
                .put("serviceState", serviceState.javaClass.name)
                .put("appliedSource", applications.javaClass.name)
                .put(
                    "reconnect",
                    reconnect.javaClass.name,
                ).put("actualAnimationsEnabled", ValueAnimator.areAnimatorsEnabled())
                .put("applicationBoundary", "Hilt test application: ready-startup and LOCAL public-IP resolver only"),
        )
        observers.launch(start = CoroutineStart.UNDISPATCHED) {
            applications.applications.collect { current ->
                current.values
                    .filterIsInstance<RuntimeConfigurationApplication.Applied>()
                    .filter { it.configuration.runtimeId !in baselineIds }
                    .forEach {
                        evidence.applied(it.configuration)
                    }
            }
        }
        observers.launch(start = CoroutineStart.UNDISPATCHED) {
            serviceState.status.collect { (status, mode) ->
                evidence.record("status", JSONObject().put("status", status.name).put("mode", mode.name))
            }
        }
        observers.launch(start = CoroutineStart.UNDISPATCHED) {
            reconnect.reconnectState.collect { evidence.record("reconnect", JSONObject().put("state", it.toString())) }
        }
    }

    @After
    fun tearDown() {
        val failures = mutableListOf<Throwable>()

        fun restore(operation: () -> Unit) {
            try {
                operation()
            } catch (failure: Exception) {
                failures += failure
            } catch (failure: AssertionError) {
                failures += failure
            }
        }
        restore {
            if (this::ownership.isInitialized) {
                if (ownsService) {
                    ownership.restore(settings, serviceController, serviceState, applications)
                } else {
                    ownership.finishUnchanged(settings)
                }
            }
        }
        restore { activity?.close() }
        restore { observers.cancel() }
        restore {
            if (this::evidence.isInitialized) {
                evidence.record(
                    "restored",
                    JSONObject()
                        .put("settingsExact", failures.isEmpty())
                        .put("serviceHalted", serviceState.status.value.first == AppStatus.Halted)
                        .put("cleanupErrors", org.json.JSONArray(failures.map { it.javaClass.simpleName })),
                )
            }
        }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    @Test
    fun vpnHomeReviewCancelStaleAndConfirmUseActualTunnelAndDns() = verify(Mode.VPN)

    @Test
    fun proxyHomeReviewCancelStaleAndConfirmUseActualNativeSocksAndDns() = verify(Mode.Proxy)

    private fun verify(mode: Mode) {
        replacementDnsProtocol = if (mode == Mode.VPN) "dot" else "doh"
        val ports = buildSet { while (size < 3) add(reserveLoopbackPort()) }.toList()
        ownedSettings =
            listOf(
                fixtureSettings(mode, ports[0], "doh"),
                fixtureSettings(mode, ports[1], replacementDnsProtocol),
                fixtureSettings(mode, ports[2], replacementDnsProtocol),
            )
        ownership.activate(
            mode,
            ownedSettings,
            baselineIds,
            runBlocking {
                serviceController.captureRuntimeSnapshot()
            }.command?.commandId,
        )
        ownsService = true
        initialReceipt = runBlocking { serviceController.prepareStart(mode) }
        ownership.recordCommand(initialReceipt.commandId)
        val original = startInitial(mode, ports[0])
        cancelReview(mode, original, ports)
        invalidateReview(mode, original, ports)
        confirmFreshReview(mode, original, ports)
    }

    private fun startInitial(
        mode: Mode,
        port: Int,
    ): AppliedRuntimeConfiguration {
        if (mode ==
            Mode.VPN
        ) {
            assertEquals("Grant genuine VPN consent before enabling TalkBack", null, VpnService.prepare(context))
        }
        runBlocking { settings.replace(ownedSettings[0]) }
        activity =
            ActivityScenario.launch(
                Intent(context, MainActivity::class.java).putExtra(AutomationLaunchContract.Enabled, false),
            )
        assertTrue(
            "Real service dispatch was rejected",
            runBlocking {
                serviceController.startPrepared(mode, initialReceipt)
            } is ServiceStartResult.Accepted,
        )
        val original = awaitApplied(mode)
        ownership.recordRuntime(original, awaitCoherentAuthority(original, initialReceipt.commandId))
        assertEquals("doh", original.dns.protocol)
        awaitRunning(mode)
        evidence.probe("initial", mode, port, "doh")
        ui.observe("initial-running")
        return original
    }

    private fun cancelReview(
        mode: Mode,
        original: AppliedRuntimeConfiguration,
        ports: List<Int>,
    ) {
        runBlocking { settings.replace(ownedSettings[1]) }
        awaitPending(mode)
        openReview("open-cancel-review")
        ui.readSection(
            "read-current",
            RipDpiTestTags.HomeRuntimeReconnectCurrent,
            context.getString(R.string.dns_protocol_doh),
        )
        ui.readSection(
            "read-saved",
            RipDpiTestTags.HomeRuntimeReconnectSaved,
            context.getString(
                if (replacementDnsProtocol ==
                    "dot"
                ) {
                    R.string.dns_protocol_dot
                } else {
                    R.string.dns_protocol_doh
                },
            ),
        )
        ui.action("cancel-review", RipDpiTestTags.HomeRuntimeReconnectDialog, context.getString(R.string.config_cancel))
        ui.awaitTag(RipDpiTestTags.HomeRuntimeReconnectDialog, false)
        assertCurrent(mode, original)
        evidence.assertListenerClosed(ports[1])
        evidence.probe("cancelled", mode, ports[0], "doh")
        assertCurrent(mode, original)
        evidence.assertOnlySessions(original.runtimeId)
    }

    private fun invalidateReview(
        mode: Mode,
        original: AppliedRuntimeConfiguration,
        ports: List<Int>,
    ) {
        openReview("open-stale-review")
        ui.observe("stale-review-ready")
        runBlocking { settings.replace(ownedSettings[2]) }
        ui.awaitTag(RipDpiTestTags.HomeRuntimeReconnectDialog, false)
        assertCurrent(mode, original)
        evidence.assertListenerClosed(ports[1])
        evidence.assertListenerClosed(ports[2])
        evidence.probe("stale", mode, ports[0], "doh")
        assertCurrent(mode, original)
        evidence.assertOnlySessions(original.runtimeId)
        ui.observe("stale-review-dismissed")
    }

    private fun confirmFreshReview(
        mode: Mode,
        original: AppliedRuntimeConfiguration,
        ports: List<Int>,
    ) {
        openReview("open-fresh-review")
        ui.action("confirm-review", "home_runtime_confirm", context.getString(R.string.oom_recovery_action_reconnect))
        val replacement = awaitApplied(mode, original.runtimeId)
        ownership.recordRuntime(replacement, awaitCoherentAuthority(replacement))
        awaitRunning(mode)
        HomeReconnectNetworkAcceptanceSupport.await("New native settings were not acknowledged as InSync", 70_000) {
            applications.pendingChanges.value[mode] == RuntimeConfigurationPendingStatus.InSync
        }
        assertNotEquals(original.runtimeId, replacement.runtimeId)
        assertEquals(replacementDnsProtocol, replacement.dns.protocol)
        ui.awaitTag(RipDpiTestTags.HomeRuntimeReconnectDialog, false)
        evidence.assertListenerClosed(ports[0])
        evidence.assertListenerClosed(ports[1])
        evidence.assertListenerOpen(ports[2])
        evidence.probe("reconnected", mode, ports[2], replacementDnsProtocol)
        evidence.assertOnlySessions(original.runtimeId, replacement.runtimeId)
        evidence.record(
            "accepted",
            JSONObject()
                .put("original", original.toEvidence())
                .put("replacement", replacement.toEvidence())
                .put("pending", applications.pendingChanges.value[mode].toString())
                .put("mode", mode.name),
        )
        ui.observe("reconnected")
    }

    private fun openReview(stage: String) {
        ui.action(stage, "home_runtime_reconnect", context.getString(R.string.oom_recovery_action_reconnect))
        ui.awaitTag(RipDpiTestTags.HomeRuntimeReconnectDialog, true)
    }

    private fun assertCurrent(
        mode: Mode,
        expected: AppliedRuntimeConfiguration,
    ) {
        assertEquals(
            expected,
            (applications.applications.value[mode] as? RuntimeConfigurationApplication.Applied)?.configuration,
        )
        assertEquals(AppStatus.Running to mode, serviceState.status.value)
        awaitPending(mode)
        ui.assertBound()
    }

    private fun awaitPending(mode: Mode) =
        HomeReconnectNetworkAcceptanceSupport.await("Saved changes were not observed") {
            applications.pendingChanges.value[mode] == RuntimeConfigurationPendingStatus.SavedChangesPending
        }

    private fun awaitRunning(mode: Mode) =
        HomeReconnectNetworkAcceptanceSupport.await("Real runtime is not running", 70_000) {
            serviceState.status.value == AppStatus.Running to mode &&
                serviceState.telemetry.value.proxyTelemetry.state == "running"
        }

    private fun awaitApplied(
        mode: Mode,
        excludedRuntime: String? = null,
    ): AppliedRuntimeConfiguration {
        var applied: AppliedRuntimeConfiguration? = null
        HomeReconnectNetworkAcceptanceSupport.await("No fresh positive native/TUN configuration receipt", 70_000) {
            applied = (applications.applications.value[mode] as? RuntimeConfigurationApplication.Applied)?.configuration
            applied != null && applied?.mode == mode && applied?.runtimeId !in baselineIds &&
                applied?.runtimeId != excludedRuntime
        }
        return checkNotNull(applied)
    }

    private fun awaitCoherentAuthority(
        configuration: AppliedRuntimeConfiguration,
        expectedCommandId: String? = null,
    ): com.poyka.ripdpi.data.RuntimeAuthoritySnapshot {
        var authority: com.poyka.ripdpi.data.RuntimeAuthoritySnapshot? = null
        HomeReconnectNetworkAcceptanceSupport.await("No coherent positive native/durable command pair", 15_000) {
            authority = runBlocking { serviceController.captureRuntimeSnapshot() }
            val current =
                (applications.applications.value[configuration.mode] as? RuntimeConfigurationApplication.Applied)
                    ?.configuration
            current == configuration && ownership.isCoherent(configuration, checkNotNull(authority)) &&
                (expectedCommandId == null || authority?.command?.commandId == expectedCommandId)
        }
        return checkNotNull(authority)
    }

    private fun fixtureSettings(
        mode: Mode,
        port: Int,
        protocol: String,
    ): AppSettings =
        AppSettingsSerializer.defaultValue
            .toBuilder()
            .apply {
                onboardingComplete = true
                biometricEnabled = false
                appTheme = "light"
                ripdpiMode = mode.preferenceValue
                hostAutolearnEnabled = false
                ipv6Enable = false
                desyncHttp = false
                desyncHttps = false
                desyncUdp = false
                enableCmdSettings = false
                setStrategyChains(emptyList(), emptyList())
                proxyIp = "127.0.0.1"
                proxyPort = port
                dnsMode = "encrypted"
                dnsProviderId = "custom"
                dnsIp = fixture.androidHost
                encryptedDnsProtocol = protocol
                encryptedDnsHost = if (protocol == "dot") fixture.fixtureDomain else fixture.androidHost
                encryptedDnsPort = if (protocol == "dot") fixture.dnsDotPort else fixture.dnsHttpPort
                encryptedDnsTlsServerName = encryptedDnsHost
                clearEncryptedDnsBootstrapIps()
                addEncryptedDnsBootstrapIps(fixture.androidHost)
                encryptedDnsDohUrl =
                    if (protocol == "doh") "http://${fixture.androidHost}:${fixture.dnsHttpPort}/dns-query" else ""
                encryptedDnsDnscryptProviderName = ""
                encryptedDnsDnscryptPublicKey = ""
                encryptedDnsTlsRootsPem = if (protocol == "dot") fixture.tlsCertificatePem else ""
                detectionCheckDnsRouteThroughProxy = false
            }.build()

    private fun requireNetworkPermissions(packageName: String) {
        if (Build.VERSION.SDK_INT >= LocalNetworkPermissionApi) {
            assertEquals(
                "Real local-network permission is required for $packageName",
                PackageManager.PERMISSION_GRANTED,
                context.packageManager.checkPermission(LocalNetworkPermission, packageName),
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && packageName == context.packageName) {
            assertEquals(
                PackageManager.PERMISSION_GRANTED,
                context.packageManager.checkPermission(Manifest.permission.POST_NOTIFICATIONS, packageName),
            )
        }
    }
}
