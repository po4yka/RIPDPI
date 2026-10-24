package com.poyka.ripdpi.e2e

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import com.poyka.ripdpi.activities.DiagnosticsXrayProviderController
import com.poyka.ripdpi.core.XrayBridgeModule
import com.poyka.ripdpi.core.XrayDatDir
import com.poyka.ripdpi.core.XrayNativeBridge
import com.poyka.ripdpi.core.XrayProtectController
import com.poyka.ripdpi.core.XrayRuntimeOwner
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.AppliedRuntimeConfigurationSource
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PauseAuthorityPersistence
import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.PausePhase
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.ProfileMutationRecoveryAccess
import com.poyka.ripdpi.data.ProfileMutationRecoveryCoordinator
import com.poyka.ripdpi.data.ProfileQualityStore
import com.poyka.ripdpi.data.ProfileUtilityCatalogReader
import com.poyka.ripdpi.data.ProfileUtilityReference
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.ResolverOverrideStore
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TlsFingerprintProfileFirefoxStable
import com.poyka.ripdpi.data.displayMessage
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.data.xray.DurableXrayProfileStore
import com.poyka.ripdpi.data.xray.XrayImportParser
import com.poyka.ripdpi.data.xray.XrayListenerState
import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayProfileRedactor
import com.poyka.ripdpi.data.xray.XrayProviderBuildInfo
import com.poyka.ripdpi.data.xray.XrayProviderProbeCoordinator
import com.poyka.ripdpi.data.xray.XrayProviderProbeKind
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import com.poyka.ripdpi.data.xray.XrayProviderSelectionStore
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.CandidateRelayMeasurements
import com.poyka.ripdpi.services.CandidateRelayNetworkEpoch
import com.poyka.ripdpi.services.CandidateXrayMeasurements
import com.poyka.ripdpi.services.MeasuredProfileActivationCoordinator
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceStartResult
import com.poyka.ripdpi.services.SplitTunnelMode
import com.poyka.ripdpi.services.TimedPauseController
import com.poyka.ripdpi.services.VpnTunnelSessionProvider
import com.poyka.ripdpi.services.VpnTunnelSessionProviderModule
import com.poyka.ripdpi.ui.screens.profiles.ProfileMeasurementUiState
import com.poyka.ripdpi.ui.screens.profiles.ProfileUtilityMeasurementCoordinator
import com.poyka.ripdpi.ui.screens.profiles.ProfileUtilityViewModel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Real VpnService, Keystore, gomobile engine and TUN; independent host peer, never a public server. */
@HiltAndroidTest
@UninstallModules(VpnTunnelSessionProviderModule::class, XrayBridgeModule::class)
class XrayProviderE2ETest {
    // JUnit creates a fresh test instance, so each case has its own eight-event budget.
    @BindValue
    @JvmField
    val vpnTunnelSessionProvider: VpnTunnelSessionProvider = ObservedVpnTunnelSessionProvider()

    // Nested test modules keep the production lazy singleton construction timing.
    @Module
    @InstallIn(SingletonComponent::class)
    object DiagnosticXrayBridgeModule {
        @Provides
        @Singleton
        @XrayDatDir
        fun provideXrayDatDir(
            @ApplicationContext context: Context,
        ): String = XrayBridgeModule.provideXrayDatDir(context)

        @Provides
        @Singleton
        fun provideXrayNativeBridge(
            @XrayDatDir datDir: String,
        ): XrayNativeBridge {
            val bridge = FixtureTrustXrayNativeBridge(XrayBridgeModule.provideXrayNativeBridge(datDir))
            return if (InstrumentationRegistry.getArguments().getString("ripdpi.xrayDebug") == "true") {
                TracedXrayNativeBridge(bridge)
            } else {
                bridge
            }
        }

        @Provides
        @Singleton
        fun provideXrayRuntimeOwner(bridge: XrayNativeBridge): XrayRuntimeOwner =
            XrayBridgeModule.provideXrayRuntimeOwner(bridge)
    }

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val notifications: TestRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            TestRule { statement, _ -> statement }
        }

    @Inject lateinit var controller: ServiceController

    @Inject lateinit var relayProfiles: RelayProfileStore

    @Inject lateinit var relayCredentials: RelayCredentialStore

    @Inject lateinit var settings: AppSettingsRepository

    @Inject lateinit var state: ServiceStateStore

    @Inject lateinit var profiles: DurableXrayProfileStore

    @Inject lateinit var selection: XrayProviderSelectionStore

    @Inject lateinit var resolverOverrides: ResolverOverrideStore

    @Inject lateinit var providerProbes: XrayProviderProbeCoordinator

    @Inject lateinit var utilityRecovery: ProfileMutationRecoveryAccess

    @Inject lateinit var utilityMutations: ProfileMutationRecoveryCoordinator

    @Inject lateinit var utilityAuthority: PauseIntentAuthority

    @Inject lateinit var utilityPersistence: PauseAuthorityPersistence

    @Inject lateinit var utilityQuality: ProfileQualityStore

    @Inject lateinit var utilityCatalog: ProfileUtilityCatalogReader

    @Inject lateinit var utilityApplied: AppliedRuntimeConfigurationSource

    @Inject lateinit var utilityPause: TimedPauseController

    @Inject lateinit var utilityEpoch: CandidateRelayNetworkEpoch

    @Inject lateinit var qualityXrayOwner: XrayRuntimeOwner

    @Inject lateinit var qualityProtection: com.poyka.ripdpi.services.ActiveProtectSocketPathProvider

    @Inject lateinit var utilityNative: CandidateRelayMeasurements

    @Inject lateinit var utilityXray: CandidateXrayMeasurements

    @Inject lateinit var utilityActivation: MeasuredProfileActivationCoordinator

    @Inject internal lateinit var utilityMeasurement:
        ProfileUtilityMeasurementCoordinator

    private val diagnosticsScope = MainScope()
    private lateinit var diagnostics: DiagnosticsXrayProviderController
    private var initialized = false
    private lateinit var previousSettings: AppSettings
    private lateinit var previousSelection: XrayProviderSelectionRecord
    private var previousProfile: XrayProfile? = null
    private lateinit var manifest: JSONObject
    private var controlPort = 0
    private var fixtureHost = "10.0.2.2"
    private var controlHost = "10.0.2.2"
    private var failureJob: Job? = null
    private var udpBridge: TestUdpBridge? = null

    @Volatile private var lastFailure: String? = null
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun prepare() {
        val configuredPort = InstrumentationRegistry.getArguments().getString("ripdpi.xrayFixturePort")
        assumeTrue("Requires independent owned Xray fixture", configuredPort != null)
        check(
            isLikelyEmulator() ||
                InstrumentationRegistry.getArguments().getString("ripdpi.authorizedPhysicalDevice") == "true",
        ) {
            "Physical acceptance requires explicit device authorization"
        }
        controlPort = requireNotNull(configuredPort).toInt().also { require(it in 1..65_535) }
        fixtureHost = InstrumentationRegistry.getArguments().getString("ripdpi.xrayFixtureHost") ?: "10.0.2.2"
        controlHost = InstrumentationRegistry.getArguments().getString("ripdpi.xrayControlHost") ?: fixtureHost
        hilt.inject()
        diagnostics = DiagnosticsXrayProviderController(diagnosticsScope, state, providerProbes)
        failureJob =
            CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
                state.events.collect { event ->
                    if (event is ServiceEvent.Failed) {
                        lastFailure = XrayProfileRedactor.redactText(event.reason.displayMessage)
                    }
                }
            }
        previousSettings = runBlocking { settings.snapshot() }
        previousSelection = runBlocking { selection.current() }
        previousProfile = runBlocking { profiles.load(ProfileId) }
        initialized = true
        runBlocking { controller.stop() }
        awaitUntil { state.status.value.first == AppStatus.Halted }
        ensureLocalNetworkAccessGranted(context)
        if (android.os.Build.VERSION.SDK_INT >= com.poyka.ripdpi.data.LocalNetworkPermissionApi) {
            ensureLocalNetworkAccessGranted(InstrumentationRegistry.getInstrumentation().context)
        }
        ensureVpnConsentGranted(context)
        manifest = readControl("manifest")
        udpBridge = TestUdpBridge.fromArguments()
        val dnsHttpPort = manifest.getInt("dnsHttpPort").also { require(it in 1..65_535) }
        runBlocking {
            settings.update {
                ripdpiMode = Mode.VPN.preferenceValue
                proxyIp = "127.0.0.1"
                proxyPort = reserveLoopbackPort()
                ipv6Enable = false
                rootModeEnabled = false
                setSplitTunnelMode(SplitTunnelMode.Off)
                clearSplitTunnelPackages()
                enableCmdSettings = false
                desyncHttp = false
                desyncHttps = false
                desyncUdp = false
                setStrategyChains(emptyList(), emptyList())
                // Same test-only HTTP DoH pattern as applyFixtureEncryptedDns.
                setDnsMode(DnsModeEncrypted)
                setDnsProviderId(DnsProviderCustom)
                setDnsIp(fixtureHost)
                setEncryptedDnsProtocol(EncryptedDnsProtocolDoh)
                setEncryptedDnsHost(fixtureHost)
                setEncryptedDnsPort(dnsHttpPort)
                setEncryptedDnsTlsServerName(fixtureHost)
                clearEncryptedDnsBootstrapIps()
                addEncryptedDnsBootstrapIps(fixtureHost)
                setEncryptedDnsDohUrl("http://$fixtureHost:$dnsHttpPort/dns-query")
                setEncryptedDnsDnscryptProviderName("")
                setEncryptedDnsDnscryptPublicKey("")
                setEncryptedDnsTlsRootsPem("")
                clearEncryptedDnsOdohProxyUrl()
                clearEncryptedDnsOdohProxyOperatorId()
                clearEncryptedDnsOdohTargetHost()
                clearEncryptedDnsOdohTargetPath()
                clearEncryptedDnsOdohTargetOperatorId()
                clearEncryptedDnsOdohConfigSource()
                clearEncryptedDnsOdohConfigsHex()
                clearEncryptedDnsOdohConfigsRetrievedAtSecs()
                clearEncryptedDnsOdohConfigsTtlSecs()
                setDetectionCheckDnsRouteThroughProxy(false)
            }
        }
    }

    @After
    fun restore() {
        try {
            if (!initialized) return
            runBlocking { controller.stop() }
            awaitUntil { state.status.value.first == AppStatus.Halted }
            runBlocking {
                previousProfile?.let { profiles.save(ProfileId, it) } ?: profiles.clear(ProfileId)
                selection.update(previousSelection)
                settings.replace(previousSettings)
            }
        } finally {
            diagnosticsScope.cancel()
            failureJob?.cancel()
            try {
                udpBridge?.close()
            } finally {
                clearTestProbeNetworkEligibility()
            }
        }
    }

    private fun describeApplication(application: RuntimeConfigurationApplication): String =
        when (application) {
            is RuntimeConfigurationApplication.Applying -> {
                "Applying(revision=${application.attempt.revision},reason=${application.attempt.reason}," +
                    "previous=${application.previous?.revision})"
            }

            is RuntimeConfigurationApplication.Applied -> {
                "Applied(revision=${application.configuration.revision},reason=${application.configuration.reason})"
            }

            else -> {
                application::class.java.simpleName
            }
        }

    private fun CoroutineScope.observeUtility(
        vm: ProfileUtilityViewModel,
        phase: MutableStateFlow<String>,
        expected: ProfileUtilityReference,
    ) = launch {
        val sampler =
            launch(Dispatchers.Default) {
                repeat(8) { sample ->
                    delay(500L)
                    val ui = vm.uiState.value
                    val job = vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]
                    val applications = utilityApplied.applications.value.mapValues { describeApplication(it.value) }
                    Log.i(
                        "ProfileUtilityNative",
                        "Sampler=$sample; phase=${phase.value}; " +
                            "catalog=${ui.catalogState}/${ui.catalogGeneration}; " +
                            "failure=${ui.failure}; " +
                            "scopeActive=${job?.isActive}; scopeCancelled=${job?.isCancelled}; " +
                            "status=${state.status.value}; applications=$applications",
                    )
                }
            }
        try {
            var logged = 0
            combine(
                vm.uiState,
                state.status,
                utilityApplied.applications,
                phase,
            ) { ui, status, applications, currentPhase ->
                val rows =
                    ui.profiles.map { row ->
                        val measurement =
                            when (val measured = row.measurement) {
                                is ProfileMeasurementUiState.Failed -> "Failed:${measured.reason}"
                                else -> measured::class.java.simpleName
                            }
                        "${row.reference::class.java.simpleName}(expected=${row.reference == expected}," +
                            "$measurement,recent=${row.recentSequence},applied=${row.applied})"
                    }
                val types = applications.mapValues { describeApplication(it.value) }
                "${this@XrayProviderE2ETest.javaClass.simpleName}:Phase=$currentPhase; " +
                    "catalog=${ui.catalogState}/${ui.catalogGeneration}; failure=${ui.failure}; " +
                    "cleanup=${ui.cleanupPending}; rows=$rows; status=$status; applications=$types"
            }.distinctUntilChanged().collect { snapshot ->
                if (logged < 64) {
                    Log.i("ProfileUtilityNative", snapshot)
                    logged++
                }
            }
        } finally {
            sampler.cancel()
        }
    }

    private fun utilityPhaseTimeout(
        phase: String,
        vm: ProfileUtilityViewModel,
        failure: kotlinx.coroutines.TimeoutCancellationException,
    ): AssertionError {
        val ui = vm.uiState.value
        val rows =
            ui.profiles.map { row ->
                when (val measurement = row.measurement) {
                    is ProfileMeasurementUiState.Failed -> "Failed:${measurement.reason}"
                    else -> measurement::class.java.simpleName
                }
            }
        val applications = utilityApplied.applications.value.mapValues { describeApplication(it.value) }
        val snapshot =
            "Phase=$phase; catalog=${ui.catalogState}/${ui.catalogGeneration}; " +
                "failure=${ui.failure}; rows=$rows; status=${state.status.value}; applications=$applications"
        Log.e("ProfileUtilityNative", snapshot)
        return AssertionError(snapshot, failure)
    }

    @Test
    fun realRealityQualityMeasuresAuthenticatedBodyAndConcurrentHeadBeforeRetirement() =
        runBlocking {
            kotlinx.coroutines.withTimeout(45_000L) {
                val original = settings.snapshot()
                val link =
                    "vless://${manifest.getString("uuid")}@$fixtureHost:${manifest.getInt("tcpPort")}" +
                        "?type=tcp&security=reality&sni=fixture.test&pbk=${manifest.getString("publicKey")}" +
                        "&sid=ab12&fp=chrome&flow=xtls-rprx-vision"
                val parsed = XrayImportParser().parse(link, XrayProviderBuildInfo.upstreamTag)
                check(parsed is XrayImportParser.Result.Accepted)
                val policy =
                    com.poyka.ripdpi.data.PayloadFailoverPolicy(
                        2,
                        "select",
                        listOf("quality-a.example.test", "quality-b.example.test").map { host ->
                            com.poyka.ripdpi.data.PayloadProbeControl(
                                "https://$host/quality-payload",
                                manifest.getInt("qualityBytes"),
                                manifest.getString("qualitySha256"),
                            )
                        },
                        60,
                        15,
                        2,
                        2,
                        120,
                        900,
                        com.poyka.ripdpi.data
                            .ProfileQualityPolicy(2_097_152, 16_777_216, 3_600, 20, 50),
                    )
                val probe =
                    com.poyka.ripdpi.services.OwnedQualityProbeFactory.xray(
                        qualityXrayOwner,
                        qualityProtection,
                        utilityNative::captureEnvironment,
                        manifest.getString("qualityCertificate"),
                    )
                val before = readControl("receipts").getInt("count")
                val directBefore = readControl("direct-receipts").getInt("count")
                val result = probe.measurePayload(parsed.profile, policy)
                assertTrue(
                    "The actual native REALITY candidate must verify TLS payload",
                    result is
                        com.poyka.ripdpi.services.CandidateRelayMeasurement.Succeeded,
                )
                val measurement =
                    requireNotNull((result as com.poyka.ripdpi.services.CandidateRelayMeasurement.Succeeded).quality)
                assertTrue("The owned GET must overlap the complete authenticated HEAD", measurement.complete)
                assertEquals(manifest.getInt("qualityBytes").toLong(), measurement.verifiedBytes)
                assertTrue(measurement.goodputBytesPerSecond > 0)
                assertEquals(before + 3, readControl("receipts").getInt("count"))
                assertEquals(directBefore, readControl("direct-receipts").getInt("count"))
                assertFalse(qualityXrayOwner.isOccupied)
                assertFalse(probe.cleanupPending.value)
                assertEquals(original, settings.snapshot())
            }
        }

    @Test
    fun profileUtilityRealRealityCandidateSelectsOnlyAfterHttpPayloadAndNativeAppliedAck() =
        runBlocking {
            kotlinx.coroutines.withTimeout(90_000L) {
                utilityRecovery.recover()
                val id = "profile-utility-owned-xray"
                val reference =
                    ProfileUtilityReference
                        .Xray(id)
                val link =
                    "vless://${manifest.getString("uuid")}@$fixtureHost:${manifest.getInt("xhttpPort")}" +
                        "?type=xhttp&security=reality&sni=fixture.test&pbk=${manifest.getString("publicKey")}" +
                        "&sid=ab12&fp=chrome&path=%2Fowned-xhttp&mode=auto"
                val parsed = XrayImportParser().parse(link, XrayProviderBuildInfo.upstreamTag)
                check(parsed is XrayImportParser.Result.Accepted) { "Owned REALITY fixture validation failed" }
                val profile = parsed.profile.copy(inbound = parsed.profile.inbound.copy(port = reserveLoopbackPort()))
                val phase = MutableStateFlow("xray/catalog")
                val models = ViewModelStore()
                var collector: Job? = null
                ActivityScenario
                    .launch(
                        com.poyka.ripdpi.activities.MainActivity::class.java,
                    ).use {
                        try {
                            utilityRecovery.mutateCatalog(
                                utilityMutations.captureMutation(
                                    ProfileMutationOrigin.ImportOnly,
                                ),
                            ) {
                                profiles.save(id, profile)
                            }
                            selection.update(XrayProviderSelectionRecord())
                            assertTrue(controller.start(Mode.VPN) is ServiceStartResult.Accepted)
                            state.status.first { it == AppStatus.Running to Mode.VPN }
                            utilityPause.pause(300_000L)
                            utilityAuthority.states.first {
                                it?.pause?.phase == PausePhase.Paused
                            }
                            kotlinx.coroutines.withTimeout(5_000L) {
                                while (utilityEpoch.capture() ==
                                    null
                                ) {
                                    kotlinx.coroutines.delay(20)
                                }
                            }
                            val before = readControl("receipts").getInt("count")
                            val directBefore = readControl("direct-receipts").getInt("count")
                            val historyBefore = checkNotNull(utilityPersistence.read()).profileUtility.lastSequence
                            val vm =
                                ProfileUtilityViewModel(
                                    utilityCatalog,
                                    utilityAuthority,
                                    utilityRecovery,
                                    utilityApplied,
                                    utilityMeasurement,
                                    utilityActivation,
                                    utilityNative,
                                    utilityXray,
                                    utilityQuality,
                                )
                            kotlinx.coroutines.withContext(Dispatchers.Main) { models.put("owned-xray-utility", vm) }
                            collector = observeUtility(vm, phase, reference)
                            phase.value = "xray/catalog-row"
                            vm.uiState.first { ui -> ui.profiles.any { row -> row.reference == reference } }
                            vm.updateUrl("http://192.0.2.77/profile-utility-owned-payload")
                            phase.value = "xray/dispatch"
                            vm.checkAndSelect(reference)
                            phase.value = "xray/measured"
                            try {
                                vm.uiState.first { ui ->
                                    ui.profiles.any { row ->
                                        row.reference == reference &&
                                            row.measurement is ProfileMeasurementUiState.Measured
                                    }
                                }
                            } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                                throw utilityPhaseTimeout("utility-measured", vm, failure)
                            }
                            phase.value = "xray/applied"
                            utilityApplied.applications.first { applications ->
                                val configured =
                                    (applications[Mode.VPN] as? RuntimeConfigurationApplication.Applied)
                                        ?.configuration
                                configured?.effectiveSelection?.provider == "xray" &&
                                    configured.effectiveSelection.profileId == id
                            }
                            phase.value = "xray/running-history"
                            state.status.first { it == AppStatus.Running to Mode.VPN }
                            phase.value = "xray/history-assertions"
                            val stored = checkNotNull(utilityPersistence.read()).profileUtility
                            assertEquals(reference, stored.recents.first().reference)
                            assertEquals(historyBefore + 1, stored.lastSequence)
                            assertEquals(1, stored.recents.count { it.reference == reference })
                            assertEquals(null, utilityAuthority.snapshot())
                            assertTrue(readControl("receipts").getInt("count") > before)
                            assertEquals(directBefore, readControl("direct-receipts").getInt("count"))
                            assertEquals(false, utilityXray.cleanupPending.value)
                        } finally {
                            collector?.cancel()
                            kotlinx.coroutines.withContext(Dispatchers.Main) { models.clear() }
                            utilityPause.stop()
                            controller.stop()
                            state.status.first { it.first == AppStatus.Halted }
                            utilityRecovery.mutateCatalog(
                                utilityMutations.captureMutation(
                                    ProfileMutationOrigin.ExplicitDeletion,
                                ),
                            ) {
                                profiles.clear(id)
                            }
                        }
                    }
            }
        }

    @Test
    fun nginxTlsAndRealityCompleteBoundedTransfersAndRecover() {
        check(InstrumentationRegistry.getArguments().getString("ripdpi.usbDataScope") != "true")
        exerciseTransportTransactions()
    }

    @Test
    fun usbNginxTlsAndRealityCompleteBoundedTransfersAndRecover() {
        check(InstrumentationRegistry.getArguments().getString("ripdpi.usbDataScope") == "true")
        exerciseTransportTransactions()
    }

    private fun exerciseTransportTransactions() {
        for (network in listOf("tcp", "xhttp-tls")) {
            start(network, wrongIdentity = false)
            awaitOwnedPeerEcho(network)
            assertOwnedDnsThroughProvider()
            bindTestProcessDnsProbeService(10_000).use { probe ->
                assertTrue("Probe UID must use the VPN default network", probe.awaitVpnDefaultNetwork(10_000))

                fun transfer(
                    operation: String,
                    suffix: String,
                ) {
                    val id = "$network-$suffix-${SystemClock.elapsedRealtime()}"
                    val result = probe.transfer(operation, id)
                    assertTrue("Transfer failed: $result", result.getBoolean("ok"))
                    assertTrue(result.getInt("uid") != Process.myUid())
                    if (operation == "stream") assertTrue(result.getLong("elapsedMs") >= 59_000)
                    awaitUntil {
                        val receipts = readControl("transport-receipts").getJSONArray("receipts")
                        (0 until receipts.length()).any { index ->
                            val receipt = receipts.getJSONObject(index)
                            receipt.getString("id") == id && receipt.getInt("bytes") == result.getInt("bytes") &&
                                receipt.getString("sha256") == result.getString("sha256")
                        }
                    }
                }
                transfer("download", "download")
                transfer("upload", "upload")
                transfer("stream", "stream")
                readControl("peer/stop", "POST")
                try {
                    assertFalse(
                        probe
                            .transfer(
                                "download",
                                "$network-stopped-${SystemClock.elapsedRealtime()}",
                            ).getBoolean("ok"),
                    )
                } finally {
                    readControl("peer/start", "POST")
                }
                awaitOwnedPeerEcho("$network-recovered")
                assertOwnedDnsThroughProvider()
                transfer("download", "recovered")
                assertEquals(0, readControl("direct-receipts").getInt("count"))
                assertTrue(state.telemetry.value.tunnelStats.txPackets > 0)
                assertTrue(state.telemetry.value.tunnelStats.rxPackets > 0)
            }
            runBlocking { controller.stop() }
            awaitServiceStatus(state, AppStatus.Halted, Mode.VPN)
        }
    }

    @Test
    fun hysteriaCompletesTransfersSleepWakeAndUdpFailureFallsBackToSavedTcp() {
        check(InstrumentationRegistry.getArguments().getString("ripdpi.usbDataScope") != "true")
        exerciseHysteriaTransactions()
    }

    @Test
    fun usbHysteriaCompletesTransfersSleepWakeAndUdpFailureFallsBackToSavedTcp() {
        check(InstrumentationRegistry.getArguments().getString("ripdpi.usbDataScope") == "true")
        exerciseHysteriaTransactions()
    }

    private fun exerciseHysteriaTransactions() {
        val relayId = "owned-hysteria-acceptance"
        val oldProfile = runBlocking { relayProfiles.load(relayId) }
        val oldCredentials = runBlocking { relayCredentials.load(relayId) }
        val transportPort = manifest.getInt("transportPort")

        fun startHysteria(pin: String) {
            runBlocking {
                controller.stop()
                relayProfiles.save(
                    RelayProfileRecord(
                        id = relayId,
                        kind = RelayKindHysteria2,
                        server = manifest.getString("hysteriaHost"),
                        serverPort = udpBridge?.port ?: manifest.getInt("hysteriaPort"),
                        serverName = "fixture.test",
                        udpEnabled = true,
                        tcpFallbackEnabled = false,
                    ),
                )
                relayCredentials.save(
                    RelayCredentialRecord(
                        profileId = relayId,
                        hysteriaPassword = manifest.getString("hysteriaPassword"),
                        hysteriaSalamanderKey = manifest.getString("hysteriaSalamander"),
                        hysteriaInsecure = true,
                        hysteriaPinSha256 = pin,
                    ),
                )
                selection.update(XrayProviderSelectionRecord())
                settings.update {
                    relayEnabled = true
                    relayKind = RelayKindHysteria2
                    relayProfileId = relayId
                    tlsFingerprintProfile = TlsFingerprintProfileFirefoxStable
                }
                assertTrue(controller.start(Mode.VPN) is ServiceStartResult.Accepted)
            }
            awaitServiceStatus(state, AppStatus.Running, Mode.VPN, timeoutMs = 30_000)
        }
        try {
            // Save and exercise the TCP reserve before the UDP path is disrupted.
            start("tcp", wrongIdentity = false)
            awaitOwnedPeerEcho("predelivered-reserve")
            runBlocking { controller.stop() }
            awaitServiceStatus(state, AppStatus.Halted, Mode.VPN)
            startHysteria("0".repeat(64))
            bindTestProcessDnsProbeService(10_000).use { probe ->
                assertTrue(probe.awaitVpnDefaultNetwork(10_000))
                val before = readControl("transport-receipts").getJSONArray("receipts").length()
                assertFalse(probe.transfer("download", "hysteria-wrong-pin", port = transportPort).getBoolean("ok"))
                assertEquals(before, readControl("transport-receipts").getJSONArray("receipts").length())
            }
            startHysteria(manifest.getString("hysteriaCertificatePin"))
            bindTestProcessDnsProbeService(10_000).use { probe ->
                assertTrue(probe.awaitVpnDefaultNetwork(10_000))

                fun transfer(
                    operation: String,
                    suffix: String,
                    port: Int = transportPort,
                ) {
                    val id = "hysteria-$suffix-${SystemClock.elapsedRealtime()}"
                    val result = probe.transfer(operation, id, port = port)
                    assertTrue("Hysteria transfer failed: $result", result.getBoolean("ok"))
                    if (operation == "stream") assertTrue(result.getLong("elapsedMs") >= 59_000)
                    assertTrue(result.getInt("uid") != Process.myUid())
                    awaitUntil {
                        val receipts = readControl("transport-receipts").getJSONArray("receipts")
                        (0 until receipts.length()).any { index ->
                            val receipt = receipts.getJSONObject(index)
                            receipt.getString("id") == id && receipt.getInt("bytes") == result.getInt("bytes") &&
                                receipt.getString("sha256") == result.getString("sha256")
                        }
                    }
                }
                val dnsBefore = readControl("dns-receipts").getInt("count")
                val dns =
                    testProcessDnsProbe(
                        queryHost = "owned.test",
                        serverHost = "192.0.2.53",
                        serverPort = manifest.getInt("dnsPort"),
                        timeoutMs = 5_000,
                    )
                assertTrue("Hysteria UDP DNS failed", dns.ok)
                assertEquals(listOf("192.0.2.77"), dns.answers)
                assertTrue(dns.probeUid != null && dns.probeUid != Process.myUid())
                assertEquals(dnsBefore + 1, readControl("dns-receipts").getInt("count"))
                transfer("download", "download")
                transfer("upload", "upload")
                val executor = Executors.newSingleThreadExecutor()
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                val wasAwake = device.isScreenOn
                try {
                    device.wakeUp()
                    awaitUntil(timeoutMs = 5_000, failureMessage = { "Screen did not wake before the stream" }) {
                        device.isScreenOn
                    }
                    val stream = executor.submit { transfer("stream", "sleep-wake") }
                    SystemClock.sleep(2_000)
                    assertFalse("Stream ended before sleep", stream.isDone)
                    device.sleep()
                    awaitUntil(timeoutMs = 5_000, failureMessage = { "Screen did not turn off during the stream" }) {
                        !device.isScreenOn
                    }
                    SystemClock.sleep(2_000)
                    device.wakeUp()
                    awaitUntil(timeoutMs = 5_000, failureMessage = { "Screen did not wake during the stream" }) {
                        device.isScreenOn
                    }
                    stream.get(100, TimeUnit.SECONDS)
                } finally {
                    executor.shutdownNow()
                    if (wasAwake) device.wakeUp() else device.sleep()
                    awaitUntil(timeoutMs = 5_000, failureMessage = { "Original screen state was not restored" }) {
                        device.isScreenOn == wasAwake
                    }
                }
                readControl("peer/stop", "POST")
                try {
                    assertFalse(
                        probe.transfer("download", "hysteria-udp-blocked", port = transportPort).getBoolean("ok"),
                    )
                    assertTrue(readControl("udp-receipts").getInt("dropped") > 0)
                    // Select the already persisted reserve through the real controller.
                    runBlocking {
                        controller.stop()
                        settings.update { relayEnabled = false }
                        assertTrue(profiles.load(ProfileId) != null)
                        selection.update(
                            XrayProviderSelectionRecord(
                                providerKind = XrayProviderSelectionRecord.ProviderKindXray,
                                activeProfileId = ProfileId,
                            ),
                        )
                        assertTrue(controller.start(Mode.VPN) is ServiceStartResult.Accepted)
                    }
                    awaitServiceStatus(state, AppStatus.Running, Mode.VPN, timeoutMs = 30_000)
                    awaitOwnedPeerEcho("udp-blocked-tcp-reserve")
                    transfer("download", "tcp-reserve", port = 80)
                    assertOwnedDnsThroughProvider()
                } finally {
                    readControl("peer/start", "POST")
                }
            }
            startHysteria(manifest.getString("hysteriaCertificatePin"))
            bindTestProcessDnsProbeService(10_000).use { probe ->
                assertTrue(probe.awaitVpnDefaultNetwork(10_000))
                assertTrue(probe.transfer("download", "hysteria-udp-recovered", port = transportPort).getBoolean("ok"))
            }
            assertTrue(state.telemetry.value.tunnelStats.txPackets > 0)
            assertTrue(state.telemetry.value.tunnelStats.rxPackets > 0)
        } finally {
            runBlocking {
                controller.stop()
                if (oldProfile == null) relayProfiles.clear(relayId) else relayProfiles.save(oldProfile)
                if (oldCredentials == null) relayCredentials.clear(relayId) else relayCredentials.save(oldCredentials)
            }
        }
    }

    @Test
    fun bothTransportsRouteDistinctUidTrafficThroughRealTunAndRestart() {
        for (network in listOf("tcp", "xhttp")) {
            start(network, wrongIdentity = false)
            val before = readControl("receipts").getInt("count")
            val response = awaitOwnedPeerEcho(network)
            assertTrue(
                "A distinct test UID must traverse VPN",
                response.probeUid != null && response.probeUid != Process.myUid(),
            )
            assertOwnedDnsThroughProvider()
            awaitUntil {
                val current = state.telemetry.value
                current.tunnelStats.txPackets > 0 && current.tunnelStats.rxPackets > 0 &&
                    readControl("receipts").getInt("count") > before
            }
            assertEquals(
                XrayListenerState.Bound,
                state.telemetry.value.xrayProviderSnapshot
                    ?.listenerState,
            )
            assertTrue(
                state.telemetry.value.xrayProviderSnapshot
                    ?.xrayVersion
                    ?.contains("26.3.27") == true,
            )
            assertLiveDiagnosticsAction()
            runBlocking { controller.stop() }
            awaitServiceStatus(state, AppStatus.Halted, Mode.VPN)
            awaitUntil { diagnostics.probeReport.value == null }
            assertNull("Stopped provider must unbind its diagnostics action", providerProbes.runProbes())
            assertFalse("TEST-NET destination must not succeed outside the provider", exchange("stopped").ok)
        }
    }

    @Test
    fun peerLossAndRecoveryPreservesTunAndRejectsDirectBypass() {
        for (network in listOf("tcp", "xhttp")) {
            assertDirectSentinelReachable("before-$network")
            start(network, wrongIdentity = false)
            awaitOwnedPeerEcho(network)
            assertOwnedEncryptedDnsThroughProvider("baseline-$network")
            val restartCount = state.telemetry.value.restartCount
            val tunnelProvider = vpnTunnelSessionProvider as ObservedVpnTunnelSessionProvider
            val establishmentCount = tunnelProvider.establishmentCount
            val resolverBefore = resolverOverrides.override.value
            val failuresBefore = state.telemetry.value.tunnelTelemetry.dnsFailuresTotal
            val stopStartedAt = SystemClock.elapsedRealtime()
            val stopAck = readControl("peer/stop", "POST")
            logRecoveryObservation(
                "stop-ack-$network",
                "startedAt=$stopStartedAt running=${stopAck.getBoolean("running")}",
            )
            try {
                val before = readControl("receipts").getInt("count")
                val directBefore = readControl("direct-receipts").getInt("count")
                assertTrue("Stopped peer must reject the payload", exchange("peer-stopped").response.isNullOrEmpty())
                val direct = exchange("peer-stopped-direct", fixtureHost, manifest.getInt("directPort"))
                assertTrue("Peer loss must not enable a direct bypass", direct.response.isNullOrEmpty())
                assertEquals(before, readControl("receipts").getInt("count"))
                assertEquals(directBefore, readControl("direct-receipts").getInt("count"))
                val dnsReceiptsBefore = readControl("dns-http-receipts").getInt("count")
                val dnsStartedAt = SystemClock.elapsedRealtime()
                val dnsFailure = testProcessDnsProbe(queryHost = "peer-owned-loss-$network.test", timeoutMs = 3_000L)
                logRecoveryObservation(
                    "outage-dns-$network",
                    "startedAt=$dnsStartedAt query=${dnsFailure.queryHost} ok=${dnsFailure.ok} " +
                        "rcode=${dnsFailure.rcode} latencyMs=${dnsFailure.latencyMs} " +
                        "dohBefore=$dnsReceiptsBefore " +
                        "dohAfter=${readControl("dns-http-receipts").getInt("count")}",
                )
                Log.i(
                    "XrayPeerRecovery",
                    "phase=peer-stopped ok=${dnsFailure.ok} rcode=${dnsFailure.rcode} " +
                        "answers=${dnsFailure.answers.size} failure=${dnsFailure.failureKind}/${dnsFailure.failureStage}",
                )
                assertTrue(
                    "Outage DNS must originate from a distinct test UID",
                    dnsFailure.probeUid != null && dnsFailure.probeUid != Process.myUid(),
                )
                assertTrue("Stopped peer must return no DNS answers", dnsFailure.answers.isEmpty())
                if (dnsFailure.ok) {
                    assertEquals("Stopped peer must return SERVFAIL", 2, dnsFailure.rcode)
                }
                assertEquals(
                    "Stopped peer must not reach the real DoH handler",
                    dnsReceiptsBefore,
                    readControl("dns-http-receipts").getInt("count"),
                )
                awaitUntil {
                    assertEquals(
                        "DNS failure must preserve real TUN",
                        establishmentCount,
                        tunnelProvider.establishmentCount,
                    )
                    assertEquals("Peer loss must preserve resolver", resolverBefore, resolverOverrides.override.value)
                    state.telemetry.value.tunnelTelemetry.dnsFailuresTotal > failuresBefore
                }
                Log.i("XrayPeerRecovery", "phase=peer-stopped dnsFailureObserved=true")
                assertEquals("Peer loss must preserve resolver", resolverBefore, resolverOverrides.override.value)
                assertEquals(
                    "DNS failure must preserve real TUN",
                    establishmentCount,
                    tunnelProvider.establishmentCount,
                )
            } finally {
                val startStartedAt = SystemClock.elapsedRealtime()
                val startAck = readControl("peer/start", "POST")
                logRecoveryObservation(
                    "start-ack-$network",
                    "startedAt=$startStartedAt running=${startAck.getBoolean("running")}",
                )
            }
            awaitOwnedPeerEcho(network)
            repeat(3) { assertOwnedEncryptedDnsThroughProvider("recovered-$network-$it") }
            assertOwnedDnsThroughProvider()
            assertEquals("Recovery must preserve resolver", resolverBefore, resolverOverrides.override.value)
            assertEquals("Recovery must preserve real TUN", establishmentCount, tunnelProvider.establishmentCount)
            Log.i("XrayPeerRecovery", "phase=peer-recovered dnsReceiptsVerified=true")
            assertEquals(restartCount, state.telemetry.value.restartCount)
            assertEquals(AppStatus.Running, state.status.value.first)
            runBlocking { controller.stop() }
            awaitServiceStatus(state, AppStatus.Halted, Mode.VPN)
            assertDirectSentinelReachable("after-$network")
        }
    }

    private fun assertLiveDiagnosticsAction() {
        assertNull("Diagnostics must not run automatically", diagnostics.probeReport.value)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { diagnostics.runProbe() }
        awaitUntil { diagnostics.probeReport.value != null && !diagnostics.probeRunning.value }
        val report = requireNotNull(diagnostics.probeReport.value)
        assertTrue(report.snapshot.xrayVersion?.contains("26.3.27") == true)
        assertEquals(XrayListenerState.Bound, report.snapshot.listenerState)
        assertEquals(
            mapOf(
                XrayProviderProbeKind.Version to true,
                XrayProviderProbeKind.ListenerReadiness to true,
                XrayProviderProbeKind.WrapperPing to true,
                XrayProviderProbeKind.StatApi to false,
            ),
            report.probes.associate { it.kind to it.ok },
        )
        val stat = report.probes.single { it.kind == XrayProviderProbeKind.StatApi }
        assertTrue(
            "Stat API must remain explicitly inapplicable",
            stat.detailRedacted?.contains("not applicable") == true,
        )
    }

    private fun assertOwnedEncryptedDnsThroughProvider(label: String) {
        val before = readControl("dns-http-receipts").getInt("count")
        val startedAt = SystemClock.elapsedRealtime()
        val response =
            testProcessDnsProbe(
                queryHost = "peer-owned-$label.test",
                serverHost = "198.18.0.53",
                timeoutMs = 3_000L,
            )
        val after = readControl("dns-http-receipts").getInt("count")
        logRecoveryObservation(
            "encrypted-dns-$label",
            "startedAt=$startedAt query=${response.queryHost} ok=${response.ok} rcode=${response.rcode} " +
                "latencyMs=${response.latencyMs} answers=${response.answers.size} " +
                "failure=${response.failureKind}/${response.failureStage} dohBefore=$before dohAfter=$after",
        )
        assertTrue(
            "Encrypted DNS must originate from a distinct test UID",
            response.probeUid != null && response.probeUid != Process.myUid(),
        )
        assertTrue(
            "Owned encrypted DNS must succeed through the active provider: ${response.failureKind}/${response.failureStage}",
            response.ok,
        )
        assertEquals(0, response.rcode)
        assertEquals("MapDNS must return one synthetic answer", 1, response.answers.size)
        assertTrue(
            "MapDNS answer must use the configured synthetic range",
            response.answers.single().startsWith("198.18."),
        )
        assertEquals(
            "Real DoH handler must receive the query",
            before + 1,
            readControl("dns-http-receipts").getInt("count"),
        )
    }

    private fun logRecoveryObservation(
        phase: String,
        detail: String,
    ) {
        val telemetry = state.telemetry.value.tunnelTelemetry
        Log.i(
            "XrayPeerRecovery",
            "phase=$phase observedAt=${SystemClock.elapsedRealtime()} $detail " +
                "dnsQueries=${telemetry.dnsQueriesTotal} dnsFailures=${telemetry.dnsFailuresTotal} " +
                "endpoint=${boundedRecoveryText(telemetry.resolverEndpoint)} " +
                "lastDnsError=${boundedRecoveryText(telemetry.lastDnsError)}",
        )
    }

    private fun boundedRecoveryText(value: String?): String {
        val redacted = XrayProfileRedactor.redactText(value.orEmpty()).replace('\n', ' ').replace('\r', ' ')
        return if (redacted.length > RecoveryTextLimit) {
            redacted.take(RecoveryTextLimit) + "[truncated]"
        } else {
            redacted
        }
    }

    private fun assertOwnedDnsThroughProvider() {
        val before = readControl("dns-receipts").getInt("count")
        val packetsBefore = state.telemetry.value.tunnelStats
        val response = testProcessDnsProbe(queryHost = "owned.test", serverHost = "192.0.2.53", timeoutMs = 3_000L)
        assertTrue(
            "DNS must originate from a distinct test UID",
            response.probeUid != null && response.probeUid != Process.myUid(),
        )
        assertTrue(
            "Owned UDP DNS query must succeed through the active provider: " +
                "failure=${response.failureKind}/${response.failureStage} errno=${response.errno} " +
                "rcode=${response.rcode} dnsFailures=${state.telemetry.value.tunnelTelemetry.dnsFailuresTotal} " +
                "dnsQueries=${state.telemetry.value.tunnelTelemetry.dnsQueriesTotal}",
            response.ok,
        )
        assertEquals(0, response.rcode)
        assertEquals(listOf("192.0.2.77"), response.answers)
        val receipts = readControl("dns-receipts")
        assertEquals(before + 1, receipts.getInt("count"))
        assertEquals("owned.test.", receipts.getString("lastQuery"))
        awaitUntil {
            val packets = state.telemetry.value.tunnelStats
            packets.txPackets > packetsBefore.txPackets && packets.rxPackets > packetsBefore.rxPackets
        }
        assertEquals(AppStatus.Running, state.status.value.first)
    }

    @Test
    fun wrongIdentityCannotReachPeerOrFallBackToDirect() {
        for (network in listOf("tcp", "xhttp")) {
            val probeUid = assertDirectSentinelReachable("before-$network")
            start(network, wrongIdentity = true)
            val before = readControl("receipts").getInt("count")
            val directBefore = readControl("direct-receipts").getInt("count")
            // TUN may acknowledge TCP before upstream authentication; no application data may return.
            assertTrue(exchange("wrong-identity").response.isNullOrEmpty())
            val telemetryBeforeDirect = state.telemetry.value
            val direct = exchange("wrong-identity-direct", fixtureHost, manifest.getInt("directPort"))
            val directAttribution =
                probeAttribution(
                    label = "wrong-identity-direct",
                    probe = direct,
                    before = telemetryBeforeDirect,
                    after = state.telemetry.value,
                )
            Log.i(AttributionLogTag, directAttribution)
            assertEquals("The same distinct UID must probe while VPN is running", probeUid, direct.probeUid)
            assertTrue(
                "A reachable direct target must return no application data through bad identity",
                direct.response.isNullOrEmpty(),
            )
            assertEquals(
                "Bad identity must not fall back to the direct target",
                directBefore,
                readControl("direct-receipts").getInt("count"),
            )
            assertEquals(before, readControl("receipts").getInt("count"))
            assertEquals(AppStatus.Running, state.status.value.first)
            runBlocking { controller.stop() }
            awaitServiceStatus(state, AppStatus.Halted, Mode.VPN)
            assertDirectSentinelReachable("after-$network")
        }
    }

    private fun probeAttribution(
        label: String,
        probe: AppProcessTcpProbeResult,
        before: ServiceTelemetrySnapshot,
        after: ServiceTelemetrySnapshot,
    ): String =
        buildString {
            append("label=").append(label)
            append(" probeUid=").append(probe.probeUid ?: "unavailable")
            append(" probeLocalPort=").append(probe.localPort ?: "unavailable")
            append(" probeOk=").append(probe.ok)
            append(" responsePresent=").append(!probe.response.isNullOrEmpty())
            append(" failureKind=").append(safeProbeDiagnostic(probe.failureKind))
            append(" failureStage=").append(safeProbeDiagnostic(probe.failureStage))
            append(" errorClass=").append(safeProbeDiagnostic(probe.errorClass))
            append(" defaultNetworkBefore=")
                .append(probe.defaultNetworkStateBefore?.wireValue ?: "unavailable")
            append(" socketConstructionStartedElapsedMs=")
                .append(probe.socketConstructionStartedAtElapsedMs ?: "unavailable")
            append(" exchangeCompletedElapsedMs=")
                .append(probe.exchangeCompletedAtElapsedMs ?: "unavailable")
            append(" defaultNetworkAfter=")
                .append(probe.defaultNetworkStateAfter?.wireValue ?: "unavailable")
            append(" tunnelTxPacketsBefore=").append(before.tunnelStats.txPackets)
            append(" tunnelTxPacketsAfter=").append(after.tunnelStats.txPackets)
            append(" tunnelRxPacketsBefore=").append(before.tunnelStats.rxPackets)
            append(" tunnelRxPacketsAfter=").append(after.tunnelStats.rxPackets)
            append(" tunnelActiveSessionsBefore=").append(before.tunnelTelemetry.activeSessions)
            append(" tunnelActiveSessionsAfter=").append(after.tunnelTelemetry.activeSessions)
            append(" tunnelTotalErrorsBefore=").append(before.tunnelTelemetry.totalErrors)
            append(" tunnelTotalErrorsAfter=").append(after.tunnelTelemetry.totalErrors)
            append(" tunnelStateBefore=").append(before.tunnelTelemetry.state)
            append(" tunnelStateAfter=").append(after.tunnelTelemetry.state)
            append(" xrayListenerStateBefore=")
                .append(before.xrayProviderSnapshot?.listenerState?.name ?: "unavailable")
            append(" xrayListenerStateAfter=")
                .append(after.xrayProviderSnapshot?.listenerState?.name ?: "unavailable")
            // ServiceStateStore has no forwarding/protect counters or per-flow ingress trace.
            append(" forwardingCounters=unavailable")
            append(" protectGeneration=unavailable")
            append(" protectCounters=unavailable")
            append(" flowAttribution=unavailable")
        }

    private fun safeProbeDiagnostic(value: String?): String =
        value
            ?.takeIf { candidate -> candidate.all { it.isLetterOrDigit() || it in "._-" } }
            ?: "unavailable"

    private fun assertDirectSentinelReachable(label: String): Int {
        val before = readControl("direct-receipts").getInt("count")
        val telemetryBeforeDirect = state.telemetry.value
        val response = exchange(label, fixtureHost, manifest.getInt("directPort"))
        val baselineAttribution =
            probeAttribution(
                label = label,
                probe = response,
                before = telemetryBeforeDirect,
                after = state.telemetry.value,
            )
        Log.i(AttributionLogTag, baselineAttribution)
        assertTrue(
            "Direct baseline must come from a distinct test UID; $baselineAttribution",
            response.probeUid != null && response.probeUid != Process.myUid(),
        )
        assertTrue(
            "Direct baseline must reach the owned sentinel; $baselineAttribution",
            response.ok && response.response.orEmpty().contains("xray-direct-sentinel"),
        )
        assertEquals(
            "The independent direct receipt must confirm the baseline",
            before + 1,
            readControl("direct-receipts").getInt("count"),
        )
        return requireNotNull(response.probeUid)
    }

    private fun start(
        network: String,
        wrongIdentity: Boolean,
    ) {
        val identity = if (wrongIdentity) "00000000-0000-4000-8000-000000000001" else manifest.getString("uuid")
        val tls = network == "xhttp-tls"
        val wireNetwork = if (tls) "xhttp" else network
        val port =
            if (tls) {
                requireNotNull(InstrumentationRegistry.getArguments().getString("ripdpi.nginxTlsPort")).toInt()
            } else {
                manifest.getInt(if (network == "tcp") "tcpPort" else "xhttpPort")
            }
        val link =
            "vless://$identity@$fixtureHost:$port?type=$wireNetwork&security=${if (tls) "tls" else "reality"}" +
                "&sni=fixture.test&fp=chrome" +
                (if (tls) "" else "&pbk=${manifest.getString("publicKey")}&sid=ab12") +
                if (wireNetwork == "xhttp") "&path=%2Fowned-xhttp&mode=auto" else "&flow=xtls-rprx-vision"
        val accepted = XrayImportParser().parse(link, XrayProviderBuildInfo.upstreamTag)
        check(accepted is XrayImportParser.Result.Accepted) { "Owned profile failed typed validation" }
        val profile = accepted.profile.copy(inbound = accepted.profile.inbound.copy(port = reserveLoopbackPort()))
        runBlocking {
            profiles.save(ProfileId, profile)
            assertEquals(profile, profiles.load(ProfileId))
            selection.update(
                XrayProviderSelectionRecord(
                    providerKind = XrayProviderSelectionRecord.ProviderKindXray,
                    activeProfileId = ProfileId,
                ),
            )
        }
        assertTrue(runBlocking { controller.start(Mode.VPN) } is ServiceStartResult.Accepted)
        awaitServiceStatus(state, AppStatus.Running, Mode.VPN, timeoutMs = 30_000L)
        awaitUntil(failureMessage = {
            val telemetry = state.telemetry.value
            "status=${state.status.value} tunnel=${telemetry.tunnelTelemetry.state} " +
                "tunnelStatus=${telemetry.tunnelTelemetryStatus.state} " +
                "cause=${telemetry.tunnelTelemetryStatus.causeClass} " +
                "detail=${XrayProfileRedactor.redactText(telemetry.tunnelTelemetryStatus.message.orEmpty())} " +
                "xray=${telemetry.xrayProviderSnapshot?.readiness}/${telemetry.xrayProviderSnapshot?.failureClass} " +
                "failure=$lastFailure"
        }) {
            state.telemetry.value.xrayProviderSnapshot
                ?.listenerState == XrayListenerState.Bound
        }
    }

    private fun exchange(
        label: String,
        host: String = "192.0.2.77",
        port: Int = 80,
    ): AppProcessTcpProbeResult =
        testProcessTcpRoundTrip(
            host,
            port,
            // The shared probe reads at most the request length, so leave room for the HTTP response headers.
            "GET /$label HTTP/1.1\r\nHost: fixture.test\r\nX-Padding: ${"x".repeat(256)}\r\nConnection: close\r\n\r\n",
            connectTimeoutMs = 2_000L,
            readTimeoutMs = 3_000L,
        )

    private fun awaitOwnedPeerEcho(network: String): AppProcessTcpProbeResult {
        var latest: AppProcessTcpProbeResult? = null
        val startedAt = SystemClock.elapsedRealtime()
        awaitUntil(
            failureMessage = {
                "Owned peer did not echo after listener bound: " +
                    "ok=${latest?.ok} uid=${latest?.probeUid} " +
                    "response=${latest?.response.orEmpty().take(128)} " +
                    "failure=${latest?.failureKind}/${latest?.failureStage}"
            },
        ) {
            latest = exchange("owned-$network")
            latest.probeUid != null &&
                latest.probeUid != Process.myUid() &&
                latest.response.orEmpty().contains("xray-owned-echo")
        }
        logRecoveryObservation(
            "tcp-ready-$network",
            "startedAt=$startedAt socketStartedAt=${latest?.socketConstructionStartedAtElapsedMs} " +
                "exchangeCompletedAt=${latest?.exchangeCompletedAtElapsedMs}",
        )
        return requireNotNull(latest)
    }

    private fun readControl(
        path: String,
        method: String = "GET",
    ): JSONObject {
        val connection = URI("http://$controlHost:$controlPort/$path").toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            connection.instanceFollowRedirects = false
            check(connection.responseCode == 200)
            connection.inputStream.use { stream ->
                val body = stream.readBytes().decodeToString()
                if (path ==
                    "transport-receipts"
                ) {
                    JSONObject().put("receipts", org.json.JSONArray(body))
                } else {
                    JSONObject(body)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Trust only the ephemeral fixture certificate; the real native engine and protect path remain active. */
    private class FixtureTrustXrayNativeBridge(
        private val delegate: XrayNativeBridge,
    ) : XrayNativeBridge by delegate {
        override fun start(jsonConfig: String): Int {
            val encoded =
                InstrumentationRegistry.getArguments().getString("ripdpi.nginxCertificate")
                    ?: return delegate.start(jsonConfig)
            val config = JSONObject(jsonConfig)
            val outbounds = config.getJSONArray("outbounds")
            for (index in 0 until outbounds.length()) {
                val stream = outbounds.getJSONObject(index).optJSONObject("streamSettings") ?: continue
                if (stream.optString("security") != "tls") continue
                val tls = stream.getJSONObject("tlsSettings")
                check(!tls.optBoolean("allowInsecure", false))
                val pem = listOf("-----BEGIN CERTIFICATE-----") + encoded.chunked(64) + "-----END CERTIFICATE-----"
                tls.put("disableSystemRoot", true)
                tls.put(
                    "certificates",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("usage", "verify")
                            .put("certificate", org.json.JSONArray(pem)),
                    ),
                )
            }
            return delegate.start(config.toString())
        }
    }

    /** Diagnostic delegation keeps the real engine, protection result and lifecycle. */
    private class TracedXrayNativeBridge(
        private val delegate: XrayNativeBridge,
    ) : XrayNativeBridge by delegate {
        private val starts = AtomicInteger()
        private val sockets = AtomicInteger()

        override fun registerProtect(controller: XrayProtectController) {
            delegate.registerProtect { fd ->
                val result = controller.protect(fd)
                Log.i(
                    "XrayNativeTrace",
                    "protect start=${starts.get()} attempt=${sockets.incrementAndGet()} fd=$fd result=$result",
                )
                result
            }
        }

        override fun start(jsonConfig: String): Int {
            val original = Json.parseToJsonElement(jsonConfig).jsonObject
            val originalLog = original["log"]?.jsonObject ?: JsonObject(emptyMap())
            val tracedLog = JsonObject(originalLog + ("loglevel" to JsonPrimitive("debug")))
            val traced = JsonObject(original + ("log" to tracedLog))
            check(original.filterKeys { it != "log" } == traced.filterKeys { it != "log" })
            check(originalLog.filterKeys { it != "loglevel" } == tracedLog.filterKeys { it != "loglevel" })
            Log.i(
                "XrayNativeTrace",
                "start generation=${starts.incrementAndGet()} configChange=loglevel-only version=${delegate.version()}",
            )
            return delegate.start(traced.toString())
        }
    }

    private companion object {
        const val RecoveryTextLimit = 512
        const val AttributionLogTag = "XrayNegativeProbe"
        const val ProfileId = "xray-provider-acceptance"
    }
}
