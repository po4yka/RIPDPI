package com.poyka.ripdpi.e2e

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.diagnostics.DnsResponseOutcome
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityChecker
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityResult
import com.poyka.ripdpi.diagnostics.dpi.DnsProbeAnswer
import com.poyka.ripdpi.diagnostics.dpi.DnsUdpProbe
import com.poyka.ripdpi.diagnostics.dpi.DohJsonAddressProbe
import com.poyka.ripdpi.diagnostics.dpi.DohResolverProfile
import com.poyka.ripdpi.diagnostics.dpi.DohWireAddressProbe
import com.poyka.ripdpi.diagnostics.dpi.DoqFailureKind
import com.poyka.ripdpi.diagnostics.dpi.DoqIntegrityProbe
import com.poyka.ripdpi.diagnostics.dpi.DoqProvider
import com.poyka.ripdpi.diagnostics.dpi.DoqVerdict
import com.poyka.ripdpi.diagnostics.dpi.DpiAssetFileProvider
import com.poyka.ripdpi.diagnostics.dpi.DpiAssetLoader
import com.poyka.ripdpi.diagnostics.dpi.NativeDoqQuicClient
import com.poyka.ripdpi.diagnostics.dpi.SemanticDnsAddressProbe
import com.poyka.ripdpi.diagnostics.rkn.SelfInfoFetcher
import com.poyka.ripdpi.platform.AndroidStringResolver
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceStartResult
import com.poyka.ripdpi.ui.screens.diagnostics.DnsIntegrityToolCard
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsDnsIntegrityState
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsDpiToolsController
import com.poyka.ripdpi.ui.screens.diagnostics.state.toUiModel
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.inject.Inject
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@HiltAndroidTest
class DoqIntegrityAndroidE2ETest {
    @get:Rule(order = 0)
    val e2eFixtureRule = E2eFixtureRule()

    @get:Rule(order = 1)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 2)
    val notificationPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 3)
    val compose = createComposeRule()

    @Inject
    lateinit var serviceController: ServiceController

    @Inject
    lateinit var settings: AppSettingsRepository

    @Inject
    lateinit var serviceState: ServiceStateStore

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()
    private lateinit var client: LocalFixtureClient
    private lateinit var fixture: FixtureManifestDto
    private var vpnStarted = false

    @Before
    fun setUp() {
        assumeTrue(
            "Use the instrumented diagnostics build with the fixture root",
            InstrumentationRegistry.getArguments().getString("ripdpi.verifiedHttpsFixtureTrust") == "true",
        )
        hiltRule.inject()
        val prepared = prepareE2eEnvironment(context)
        client = prepared.fixtureClient
        fixture = prepared.fixture
    }

    @After
    fun tearDown() {
        if (vpnStarted) runBlocking { serviceController.stop() }
        if (this::client.isInitialized) {
            client.resetFaults()
            client.resetEvents()
        }
    }

    @Test
    fun doqAndDohAgreeOnTheAppRouteWithAnActiveVpn() {
        assertEquals(AppStatus.Halted, serviceState.status.value.first)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val tool = showDnsIntegrityTool(scope)
            assertToolAgreement(tool)
            val directPeer = doqPeer()
            assertDohObserved()
            client.resetEvents()

            startVpn()
            val connectivity = checkNotNull(context.getSystemService(ConnectivityManager::class.java))
            val network = checkNotNull(connectivity.activeNetwork)
            val capabilities = checkNotNull(connectivity.getNetworkCapabilities(network))
            assertTrue(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))

            assertToolAgreement(tool)
            assertEquals(directPeer, doqPeer())
            assertDohObserved()
            awaitServiceStatus(serviceState, AppStatus.Running, Mode.VPN, client)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun doqTimeoutKeepsTheIndependentDohAnswer() {
        stallDoq()
        val result = runBlocking { checker(timeoutMs = 1_500).check(listOf(fixture.fixtureDomain)) }
        val doq = result.doqResults.single()

        assertEquals(DoqVerdict.DOQ_TIMEOUT, doq.verdict)
        assertEquals(DoqFailureKind.TIMEOUT, doq.failureKind)
        assertNull(doq.response)
        assertEquals(
            DnsResponseOutcome.ANSWER,
            result.domains
                .single()
                .dohJsonResponse
                ?.outcome,
        )
        assertTrue(result.doqIncomplete)
        val row = result.toUiModel(AndroidStringResolver(context)).doqRows.single()
        assertEquals(context.getString(R.string.diagnostics_doq_timeout), row.verdict)
        assertDohObserved()
        doqPeer()
    }

    @Test
    fun cancellingAStalledDoqExchangeAllowsTheNextCheck() {
        stallDoq()
        val checker = checker()
        runBlocking {
            val active = async(Dispatchers.IO) { checker.check(listOf(fixture.fixtureDomain)) }
            try {
                awaitUntil(timeoutMs = 5_000) {
                    client.events().any { it.service == "dns_doq" && it.detail == "fault:DnsTimeout" }
                }
                withTimeout(3_000) { active.cancelAndJoin() }
                assertTrue(active.isCancelled)
            } finally {
                active.cancel()
            }
            client.resetFaults()
            client.resetEvents()
            assertAgreement(checker.check(listOf(fixture.fixtureDomain)))
            doqPeer()
            assertDohObserved()
        }
    }

    private fun stallDoq() {
        client.setFault(
            FixtureFaultSpecDto(
                target = FixtureFaultTargetDto.DNS_DOQ,
                outcome = FixtureFaultOutcomeDto.DNS_TIMEOUT,
                scope = FixtureFaultScopeDto.ONE_SHOT,
                delayMs = 20_000,
            ),
        )
    }

    private fun checker(timeoutMs: Long = 15_000): DnsIntegrityChecker {
        val jsonProbe =
            DohJsonAddressProbe(
                client = fixtureDohClient(fixture),
                endpoints =
                    listOf(
                        "https://${fixture.fixtureDomain}:${requireNotNull(fixture.verifiedHttpsPort)}/dns-query",
                    ),
            )
        // Both fixture endpoints implement the same resolver policy and return the same record.
        val policy = DohResolverProfile.CLOUDFLARE_STANDARD
        return DnsIntegrityChecker(
            udpProbe = DnsUdpProbe { emptyList() },
            dohJsonProbe =
                object : SemanticDnsAddressProbe {
                    override suspend fun resolveResponse(
                        domain: String,
                        excludedDohHostnames: Set<String>,
                    ): DnsProbeAnswer =
                        jsonProbe.resolveResponse(domain, excludedDohHostnames).copy(dohProfile = policy)
                },
            dohWireProbe = DohWireAddressProbe(endpoints = emptyList()),
            doqProbe =
                DoqIntegrityProbe(
                    NativeDoqQuicClient(),
                    providers =
                        listOf(
                            DoqProvider(
                                "Fixture",
                                fixture.androidHost,
                                fixture.dnsDoqPort,
                                fixture.fixtureDomain,
                                policy,
                            ),
                        ),
                    timeoutMs = timeoutMs,
                ),
        )
    }

    private fun startVpn() {
        ensureVpnConsentGranted(context)
        runBlocking {
            settings.update {
                proxyIp = "127.0.0.1"
                proxyPort = reserveLoopbackPort()
            }
            assertTrue(serviceController.start(Mode.VPN) is ServiceStartResult.Accepted)
            vpnStarted = true
        }
        awaitServiceStatus(serviceState, AppStatus.Running, Mode.VPN, client)
    }

    private fun showDnsIntegrityTool(scope: CoroutineScope): DiagnosticsDpiToolsController {
        val fixtureAssets =
            object : DpiAssetFileProvider {
                override fun overrideFile(relativePath: String) = File(context.cacheDir, "doq-fixture/$relativePath")

                override fun openAsset(relativePath: String) =
                    fixture.fixtureDomain.byteInputStream().also { check(relativePath == "dpi/domains.txt") }
            }
        val tool =
            DiagnosticsDpiToolsController(
                scope = scope,
                appSettingsRepository = settings,
                dnsIntegrityChecker = checker(),
                selfInfoFetcher = SelfInfoFetcher(),
                assetLoader = DpiAssetLoader(fixtureAssets),
                stringResolver = AndroidStringResolver(context),
            )
        compose.setContent {
            val result by tool.dnsIntegrityTool.collectAsStateWithLifecycle()
            RipDpiTheme(themePreference = "light") {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DnsIntegrityToolCard(tool = result, onRun = tool::runDnsIntegrityCheck)
                }
            }
        }
        return tool
    }

    private fun assertToolAgreement(tool: DiagnosticsDpiToolsController) {
        compose
            .onNodeWithText(context.getString(R.string.diagnostics_tool_dns_integrity_run))
            .performScrollTo()
            .performClick()
        val result =
            runBlocking {
                withTimeout(20_000) {
                    tool.dnsIntegrityTool.first { it.state != DiagnosticsDnsIntegrityState.Running }
                }
            }
        assertEquals(result.errorMessage, DiagnosticsDnsIntegrityState.Complete, result.state)
        compose.onNodeWithText("DoH ${fixture.dnsAnswerIpv4}", substring = true).performScrollTo().assertIsDisplayed()
        val label =
            context.getString(
                R.string.diagnostics_dns_integrity_doq_label,
                "Fixture",
                fixture.fixtureDomain,
                context.getString(R.string.diagnostics_doq_ok),
            )
        compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        val reply = "${fixture.androidHost} · ${fixture.dnsAnswerIpv4}"
        compose.onNodeWithText(reply, substring = true).performScrollTo().assertIsDisplayed()
    }

    private fun assertAgreement(result: DnsIntegrityResult) {
        val doq = result.doqResults.single()
        assertEquals(DnsResponseOutcome.ANSWER, doq.response?.outcome)
        assertEquals(DoqVerdict.DOQ_OK, doq.verdict)
        assertTrue(doq.baseline.any { it.isUsableForComparison })
        assertEquals(listOf(fixture.dnsAnswerIpv4), doq.resolvedIps)
    }

    private fun assertDohObserved() {
        assertTrue(
            client.events().any {
                it.service == "dns_doh" && it.protocol == "https" &&
                    it.detail == "/dns-query?name=${fixture.fixtureDomain}&type=A"
            },
        )
    }

    private fun doqPeer(): String {
        val query = client.events().single { it.service == "dns_doq" && it.detail == fixture.fixtureDomain }
        return query.peer.substringBeforeLast(':')
    }
}

private fun fixtureDohClient(fixture: FixtureManifestDto): OkHttpClient {
    val root =
        CertificateFactory.getInstance("X.509").generateCertificate(fixture.tlsCertificatePem.byteInputStream())
    val keyStore =
        KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("fixture", root)
        }
    val trustManager =
        TrustManagerFactory
            .getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .single()
    val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
    return OkHttpClient
        .Builder()
        .sslSocketFactory(tls.socketFactory, trustManager)
        .dns(
            Dns { name ->
                require(name == fixture.fixtureDomain)
                listOf(InetAddress.getByName(fixture.androidHost))
            },
        ).build()
}
