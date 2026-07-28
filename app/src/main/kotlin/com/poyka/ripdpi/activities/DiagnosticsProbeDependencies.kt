package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.dpi.DnsAvailabilitySurvey
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityChecker
import com.poyka.ripdpi.diagnostics.dpi.DomainReachabilityScanner
import com.poyka.ripdpi.diagnostics.dpi.DpiAssetLoader
import com.poyka.ripdpi.diagnostics.dpi.EchTlsHandshake
import com.poyka.ripdpi.diagnostics.dpi.Tcp16FatHeaderProbe
import com.poyka.ripdpi.diagnostics.dpich.CidrWhitelistDetector
import com.poyka.ripdpi.diagnostics.dpich.HttpCompressionProber
import com.poyka.ripdpi.diagnostics.dpich.Ipv4WhitelistedSubnetDiscoverer
import com.poyka.ripdpi.diagnostics.dpich.TlsKeylogRunFinalizer
import com.poyka.ripdpi.diagnostics.rkn.RknLayeredProbePipeline
import com.poyka.ripdpi.diagnostics.rkn.SelfInfoFetcher
import javax.inject.Inject

/**
 * Injected bundle of the standalone DPI probe collaborators shared by the
 * diagnostics tool controllers.
 */
internal class DiagnosticsProbeDependencies
    @Inject
    constructor(
        val dnsIntegrityChecker: DnsIntegrityChecker,
        val dnsAvailabilitySurvey: DnsAvailabilitySurvey,
        val domainReachabilityScanner: DomainReachabilityScanner,
        val tcp16FatHeaderProbe: Tcp16FatHeaderProbe,
        val httpCompressionProber: HttpCompressionProber,
        val cidrWhitelistDetector: CidrWhitelistDetector,
        val ipv4WhitelistedSubnetDiscoverer: Ipv4WhitelistedSubnetDiscoverer,
        val rknLayeredProbePipeline: RknLayeredProbePipeline,
        val selfInfoFetcher: SelfInfoFetcher,
        val assetLoader: DpiAssetLoader,
        val tlsKeylogRunFinalizer: TlsKeylogRunFinalizer,
        val echTlsHandshake: EchTlsHandshake,
        val remoteDeviceAcceptance: DiagnosticsRemoteDeviceAcceptance,
    )
