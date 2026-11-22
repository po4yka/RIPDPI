package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.diagnostics.application.DiagnosticsScanOrigin
import com.poyka.ripdpi.diagnostics.application.DiagnosticsScanRequestFactory
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import com.poyka.ripdpi.diagnostics.domain.ScanContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsEndpointScanRequestFactoryTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `tls hop starts from the empty catalog template with the user endpoint`() =
        runTest {
            val config = TlsHopComparisonConfig("www.cloudflare.com", "www.cloudflare.com", "example.com")

            val intent =
                prepareEndpointScan(
                    template =
                        endpointTemplate(TlsHopComparisonProfileId).copy(tlsHopComparison = TlsHopComparisonConfig()),
                    overrides = DiagnosticsScanTargetOverrides(tlsHopComparison = config),
                )

            assertEquals(config, intent.tlsHopComparison)
        }

    @Test
    fun `quic version matrix starts from the empty catalog template with the user endpoint`() =
        runTest {
            val config =
                QuicVersionMatrixConfig(host = "www.cloudflare.com", controlSni = "a.example", testSni = "b.example")

            val intent =
                prepareEndpointScan(
                    template =
                        endpointTemplate(
                            QuicVersionMatrixProfileId,
                        ).copy(quicVersionMatrix = QuicVersionMatrixConfig()),
                    overrides = DiagnosticsScanTargetOverrides(quicVersionMatrix = config),
                )

            assertEquals(config, intent.quicVersionMatrix)
        }

    @Test
    fun `verified transfer endpoints are bound before the empty catalog template is validated`() =
        runTest {
            val upload = VerifiedUploadInput("https://control.example/receipt", "128")
            val uploadIntent =
                prepareEndpointScan(
                    template = endpointTemplate(VerifiedUploadProfileId),
                    overrides = DiagnosticsScanTargetOverrides(verifiedUpload = upload),
                )
            val uploadTarget = uploadIntent.throughputTargets.single()
            assertEquals(upload.url, uploadTarget.url)
            assertEquals(128, uploadTarget.uploadBodyBytes)
            assertNull(uploadIntent.routeProbe)

            val download = VerifiedDownloadInput("https://control.example/body", "128", "a".repeat(64))
            val downloadIntent =
                prepareEndpointScan(
                    template = endpointTemplate(VerifiedDownloadProfileId),
                    overrides = DiagnosticsScanTargetOverrides(verifiedDownload = download),
                )
            val downloadTarget = downloadIntent.throughputTargets.single()
            assertEquals(download.url, downloadTarget.url)
            assertEquals(DownloadVerification(128, "a".repeat(64)), downloadTarget.verification)
            assertNull(downloadIntent.routeProbe)
        }

    @Test
    fun `ECH comparison binds the manual endpoint before the catalog template is validated`() =
        runTest {
            val input =
                VerifiedTransferComparisonInput("https://control.example/body", "2001:db8::7", "128", "a".repeat(64))
            val intent =
                prepareEndpointScan(
                    template = endpointTemplate(EchTransferComparisonProfileId),
                    overrides = DiagnosticsScanTargetOverrides(echComparison = input),
                )
            val target = intent.throughputTargets.single()
            assertEquals(input.url, target.url)
            assertEquals(DownloadVerification(128, input.sha256, input.connectIp), target.verification)
            assertNull(intent.routeProbe)
        }

    @Test
    fun `HTTP3 comparison binds the manual endpoint before the catalog template is validated`() =
        runTest {
            val input =
                VerifiedTransferComparisonInput("https://fixture.example:4443/body", "192.0.2.7", "128", "a".repeat(64))
            val intent =
                prepareEndpointScan(
                    template = endpointTemplate(Http3TransferComparisonProfileId).copy(quicTargets = emptyList()),
                    overrides = DiagnosticsScanTargetOverrides(http3Comparison = input),
                )
            val target = intent.throughputTargets.single()
            assertEquals(Http3TransferComparisonProfileId, target.id)
            assertEquals(input.url, target.url)
            assertEquals(input.connectIp, target.connectIp)
            assertEquals(DownloadVerification(128, input.sha256), target.verification)
            assertNull(intent.routeProbe)
        }

    @Test
    fun `volume comparison binds both manual endpoints before validating the empty catalog template`() =
        runTest {
            val input =
                TransferVolumeComparisonInput(
                    connectIp = "192.0.2.7",
                    short = VerifiedDownloadInput("https://fixture.example:4443/short", "128", "a".repeat(64)),
                    long = VerifiedDownloadInput("https://fixture.example:4443/long", "256", "b".repeat(64)),
                )
            val intent =
                prepareEndpointScan(
                    template = endpointTemplate(TransferVolumeComparisonProfileId).copy(quicTargets = emptyList()),
                    overrides = DiagnosticsScanTargetOverrides(volumeComparison = input),
                )
            assertEquals(listOf(input.short.url, input.long.url), intent.throughputTargets.map { it.url })
            assertEquals(listOf(input.connectIp, input.connectIp), intent.throughputTargets.map { it.connectIp })
            assertNull(intent.routeProbe)
        }

    private fun endpointTemplate(profileId: String): DiagnosticsIntent =
        strategyProbeIntent(settings = defaultDiagnosticsAppSettings(), baseProxyConfigJson = null).copy(
            profileId = profileId,
            kind = ScanKind.CONNECTIVITY,
            strategyProbe = null,
            domainTargets = emptyList(),
        )

    private suspend fun prepareEndpointScan(
        template: DiagnosticsIntent,
        overrides: DiagnosticsScanTargetOverrides,
    ): DiagnosticsIntent {
        val settings = defaultDiagnosticsAppSettings()
        val factory =
            DiagnosticsScanRequestFactory(
                networkEpochProvider = FakeDiagnosticsNetworkEpochProvider(),
                networkFingerprintProvider = FakeNetworkFingerprintProvider(),
                context = TestContext(),
                networkMetadataProvider = FakeNetworkMetadataProvider(),
                intentResolver =
                    object : DiagnosticsIntentResolver {
                        override suspend fun resolve(
                            profileId: String,
                            pathMode: ScanPathMode,
                        ): DiagnosticsIntent = template
                    },
                scanContextCollector =
                    object : ScanContextCollector {
                        override suspend fun collect(intent: DiagnosticsIntent): ScanContext =
                            strategyProbeContext(settings = settings, preferredDnsPath = null)
                    },
                diagnosticsPlanner = DefaultDiagnosticsPlanner(),
                engineRequestEncoder = DefaultEngineRequestEncoder(),
                activeProbeSafetyPolicy = ActiveProbeSafetyPolicy(),
                targetOptimizer = DiagnosticsTargetOptimizer(),
                json = json,
            )
        return factory
            .prepareScan(
                profile =
                    DiagnosticProfileEntity(
                        id = template.profileId,
                        name = template.profileId,
                        source = "test",
                        version = 1,
                        requestJson = "{}",
                        updatedAt = 1L,
                    ),
                settings = settings,
                pathMode = ScanPathMode.RAW_PATH,
                scanOrigin = DiagnosticsScanOrigin.USER_INITIATED,
                exposeProgress = false,
                registerActiveBridge = false,
                targetOverrides = overrides,
            ).intent
    }
}
