package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.DnsProviderGoogle
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.canonicalDefaultEncryptedDnsSettings
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanRequestWire
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import com.poyka.ripdpi.diagnostics.domain.ExecutionPolicy
import com.poyka.ripdpi.diagnostics.domain.ScanContext
import com.poyka.ripdpi.diagnostics.domain.StrategyProbeTargetCohortSpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsScanRequestFactoryTest {
    private val json = diagnosticsTestJson()
    private val contextProvider = FakeDiagnosticsContextProvider()

    @Test
    fun `strategy probe injects canonical default runtime context when active dns is plain and no preference exists`() =
        runTest {
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setDnsMode(DnsModePlainUdp)
                    .setDnsProviderId(DnsProviderCustom)
                    .setDnsIp("9.9.9.9")
                    .build()

            val request = prepareStrategyProbeRequest(settings = settings)
            val runtimeDns = decodeRuntimeDns(request)
            val defaultDns = canonicalDefaultEncryptedDnsSettings()

            assertEquals(defaultDns.providerId, runtimeDns.resolverId)
            assertEquals(defaultDns.encryptedDnsProtocol, runtimeDns.protocol)
            assertEquals(defaultDns.encryptedDnsHost, runtimeDns.host)
            assertEquals(defaultDns.encryptedDnsPort, runtimeDns.port)
            assertEquals(defaultDns.encryptedDnsTlsServerName, runtimeDns.tlsServerName)
            assertEquals(defaultDns.encryptedDnsBootstrapIps, runtimeDns.bootstrapIps)
            assertEquals(defaultDns.encryptedDnsDohUrl, runtimeDns.dohUrl)
        }

    @Test
    fun `strategy probe prefers remembered dns path over canonical default`() =
        runTest {
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setDnsMode(DnsModePlainUdp)
                    .setDnsProviderId(DnsProviderCustom)
                    .setDnsIp("9.9.9.9")
                    .build()
            val preferredPath =
                EncryptedDnsPathCandidate(
                    resolverId = DnsProviderGoogle,
                    resolverLabel = "Google Public DNS",
                    protocol = EncryptedDnsProtocolDot,
                    host = "dns.google",
                    port = 853,
                    tlsServerName = "dns.google",
                    bootstrapIps = listOf("8.8.8.8", "8.8.4.4"),
                )

            val request = prepareStrategyProbeRequest(settings = settings, preferredDnsPath = preferredPath)
            val runtimeDns = decodeRuntimeDns(request)

            assertEquals(preferredPath.resolverId, runtimeDns.resolverId)
            assertEquals(preferredPath.protocol, runtimeDns.protocol)
            assertEquals(preferredPath.host, runtimeDns.host)
            assertEquals(preferredPath.port, runtimeDns.port)
            assertEquals(preferredPath.tlsServerName, runtimeDns.tlsServerName)
            assertEquals(preferredPath.bootstrapIps, runtimeDns.bootstrapIps)
        }

    @Test
    fun `strategy probe keeps active encrypted dns ahead of remembered path`() =
        runTest {
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setDnsMode(DnsModeEncrypted)
                    .setDnsProviderId(DnsProviderGoogle)
                    .setDnsIp("8.8.8.8")
                    .setEncryptedDnsProtocol(EncryptedDnsProtocolDot)
                    .setEncryptedDnsHost("dns.google")
                    .setEncryptedDnsPort(853)
                    .setEncryptedDnsTlsServerName("dns.google")
                    .clearEncryptedDnsBootstrapIps()
                    .addAllEncryptedDnsBootstrapIps(listOf("8.8.8.8", "8.8.4.4"))
                    .setEncryptedDnsDohUrl("")
                    .build()
            val preferredPath =
                EncryptedDnsPathCandidate(
                    resolverId = DnsProviderCloudflare,
                    resolverLabel = "Cloudflare",
                    protocol = EncryptedDnsProtocolDoh,
                    host = "cloudflare-dns.com",
                    port = 443,
                    tlsServerName = "cloudflare-dns.com",
                    bootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
                    dohUrl = "https://cloudflare-dns.com/dns-query",
                )

            val request = prepareStrategyProbeRequest(settings = settings, preferredDnsPath = preferredPath)
            val runtimeDns = decodeRuntimeDns(request)

            assertEquals(DnsProviderGoogle, runtimeDns.resolverId)
            assertEquals(EncryptedDnsProtocolDot, runtimeDns.protocol)
            assertEquals("dns.google", runtimeDns.host)
            assertEquals(853, runtimeDns.port)
            assertEquals("dns.google", runtimeDns.tlsServerName)
            assertEquals(listOf("8.8.8.8", "8.8.4.4"), runtimeDns.bootstrapIps)
        }

    @Test
    fun `strategy probe preserves explicit base proxy config json`() =
        runTest {
            val explicitBaseConfigJson = "already-set"

            val request =
                prepareStrategyProbeRequest(
                    settings = defaultDiagnosticsAppSettings(),
                    baseProxyConfigJson = explicitBaseConfigJson,
                )

            assertEquals(explicitBaseConfigJson, request.strategyProbe?.baseProxyConfigJson)
        }

    @Test
    fun `automatic audit request selects one cohort and records target selection`() =
        runTest {
            val request =
                prepareStrategyProbeRequest(
                    settings = defaultDiagnosticsAppSettings(),
                    profileId = "automatic-audit",
                    family = DiagnosticProfileFamily.AUTOMATIC_AUDIT,
                    suiteId = "full_matrix_v1",
                    scanOrigin = DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND,
                    strategyProbeTargetCohorts =
                        listOf(
                            auditCohort(
                                id = "global-core",
                                label = "Global core",
                                domainHosts = listOf("www.youtube.com", "discord.com", "proton.me"),
                                quicHosts = listOf("www.youtube.com", "discord.com"),
                            ),
                            auditCohort(
                                id = "media-messaging",
                                label = "Media and messaging",
                                domainHosts = listOf("meduza.io", "telegram.org", "signal.org"),
                                quicHosts = listOf("discord.com", "www.whatsapp.com"),
                            ),
                        ),
                )

            val selection = requireNotNull(request.strategyProbe?.targetSelection)

            assertEquals(3, request.domainTargets.size)
            assertEquals(2, request.quicTargets.size)
            assertTrue(selection.cohortId in listOf("global-core", "media-messaging"))
            assertEquals(request.domainTargets.map(DomainTarget::host), selection.domainHosts)
            assertEquals(request.quicTargets.map(QuicTarget::host), selection.quicHosts)
        }

    @Test
    fun `different session ids can produce different automatic audit cohorts`() {
        val intent =
            strategyProbeIntent(
                settings = defaultDiagnosticsAppSettings(),
                baseProxyConfigJson = null,
                profileId = "automatic-audit",
                family = DiagnosticProfileFamily.AUTOMATIC_AUDIT,
                suiteId = "full_matrix_v1",
                strategyProbeTargetCohorts =
                    listOf(
                        auditCohort(
                            id = "global-core",
                            label = "Global core",
                            domainHosts = listOf("www.youtube.com", "discord.com", "proton.me"),
                            quicHosts = listOf("www.youtube.com", "discord.com"),
                        ),
                        auditCohort(
                            id = "media-messaging",
                            label = "Media and messaging",
                            domainHosts = listOf("meduza.io", "telegram.org", "signal.org"),
                            quicHosts = listOf("discord.com", "www.whatsapp.com"),
                        ),
                    ),
            )

        val firstSelection =
            requireNotNull(
                selectStrategyProbeTargetsForSession("audit-session-1", intent).strategyProbe?.targetSelection,
            )
        val secondSelection =
            generateSequence(2) { it + 1 }
                .map { attempt ->
                    requireNotNull(
                        selectStrategyProbeTargetsForSession(
                            "audit-session-$attempt",
                            intent,
                        ).strategyProbe?.targetSelection,
                    )
                }.first { selection -> selection.cohortId != firstSelection.cohortId }

        assertNotEquals(firstSelection.cohortId, secondSelection.cohortId)
    }

    @Test
    fun `automatic audit falls back to fixed targets when cohorts are invalid`() {
        val intent =
            strategyProbeIntent(
                settings = defaultDiagnosticsAppSettings(),
                baseProxyConfigJson = null,
                profileId = "automatic-audit",
                family = DiagnosticProfileFamily.AUTOMATIC_AUDIT,
                suiteId = "full_matrix_v1",
                domainTargets =
                    listOf(
                        DomainTarget(host = "fallback-a.example"),
                        DomainTarget(host = "fallback-b.example"),
                        DomainTarget(host = "fallback-c.example"),
                    ),
                quicTargets =
                    listOf(
                        QuicTarget(host = "fallback-quic-a.example"),
                        QuicTarget(host = "fallback-quic-b.example"),
                    ),
                strategyProbeTargetCohorts =
                    listOf(
                        auditCohort(
                            id = "invalid",
                            label = "Invalid",
                            domainHosts = listOf("only-one.example"),
                            quicHosts = listOf("only-one-quic.example"),
                        ),
                    ),
            )

        val selected = selectStrategyProbeTargetsForSession("audit-session", intent)

        assertEquals(intent.domainTargets.map(DomainTarget::host), selected.domainTargets.map(DomainTarget::host))
        assertEquals(intent.quicTargets.map(QuicTarget::host), selected.quicTargets.map(QuicTarget::host))
        assertNull(selected.strategyProbe?.targetSelection)
    }

    @Test
    fun `manual analysis merges all valid cohorts`() {
        val intent =
            strategyProbeIntent(
                settings = defaultDiagnosticsAppSettings(),
                baseProxyConfigJson = null,
                profileId = "automatic-audit",
                family = DiagnosticProfileFamily.AUTOMATIC_AUDIT,
                suiteId = "full_matrix_v1",
                strategyProbeTargetCohorts =
                    listOf(
                        auditCohort(
                            id = "global-core",
                            label = "Global core",
                            domainHosts = listOf("www.youtube.com", "discord.com", "proton.me"),
                            quicHosts = listOf("www.youtube.com", "discord.com"),
                        ),
                        auditCohort(
                            id = "media-messaging",
                            label = "Media and messaging",
                            domainHosts = listOf("meduza.io", "telegram.org", "signal.org"),
                            quicHosts = listOf("discord.com", "www.whatsapp.com"),
                        ),
                    ),
            )

        val selected = selectStrategyProbeTargetsForSession("session-1", intent, isManual = true)
        val selection = requireNotNull(selected.strategyProbe?.targetSelection)

        assertEquals("all", selection.cohortId)
        assertEquals("All cohorts", selection.cohortLabel)
        // 6 unique domain hosts (3+3, no overlap)
        assertEquals(6, selected.domainTargets.size)
        // 3 unique QUIC hosts (discord.com appears in both cohorts)
        assertEquals(3, selected.quicTargets.size)
        assertEquals(6, selection.domainHosts.size)
        assertEquals(3, selection.quicHosts.size)
    }

    @Test
    fun `manual analysis deduplicates overlapping hosts`() {
        val intent =
            strategyProbeIntent(
                settings = defaultDiagnosticsAppSettings(),
                baseProxyConfigJson = null,
                profileId = "automatic-audit",
                family = DiagnosticProfileFamily.AUTOMATIC_AUDIT,
                suiteId = "full_matrix_v1",
                strategyProbeTargetCohorts =
                    listOf(
                        auditCohort(
                            id = "cohort-a",
                            label = "Cohort A",
                            domainHosts = listOf("shared-a.example", "shared-b.example", "unique-a.example"),
                            quicHosts = listOf("shared-quic.example", "unique-quic-a.example"),
                        ),
                        auditCohort(
                            id = "cohort-b",
                            label = "Cohort B",
                            domainHosts = listOf("shared-a.example", "shared-b.example", "unique-b.example"),
                            quicHosts = listOf("shared-quic.example", "unique-quic-b.example"),
                        ),
                    ),
            )

        val selected = selectStrategyProbeTargetsForSession("session-2", intent, isManual = true)
        val selection = requireNotNull(selected.strategyProbe?.targetSelection)

        assertEquals("all", selection.cohortId)
        assertEquals("All cohorts", selection.cohortLabel)
        // shared-a and shared-b appear in both cohorts but are deduplicated;
        // unique-a and unique-b each appear once; total = 4 unique hosts
        val domainHosts = selected.domainTargets.map(DomainTarget::host)
        assertEquals(4, domainHosts.size)
        assertTrue(domainHosts.contains("shared-a.example"))
        assertTrue(domainHosts.contains("shared-b.example"))
        assertTrue(domainHosts.contains("unique-a.example"))
        assertTrue(domainHosts.contains("unique-b.example"))
        // shared-quic.example appears in both cohorts but is deduplicated;
        // unique-quic-a.example and unique-quic-b.example each appear once; total = 3 unique
        val quicHosts = selected.quicTargets.map(QuicTarget::host)
        assertEquals(3, quicHosts.size)
        assertTrue(quicHosts.contains("shared-quic.example"))
        assertTrue(quicHosts.contains("unique-quic-a.example"))
        assertTrue(quicHosts.contains("unique-quic-b.example"))
    }

    @Test
    fun `non-manual analysis still rotates cohorts`() {
        val intent =
            strategyProbeIntent(
                settings = defaultDiagnosticsAppSettings(),
                baseProxyConfigJson = null,
                profileId = "automatic-audit",
                family = DiagnosticProfileFamily.AUTOMATIC_AUDIT,
                suiteId = "full_matrix_v1",
                strategyProbeTargetCohorts =
                    listOf(
                        auditCohort(
                            id = "global-core",
                            label = "Global core",
                            domainHosts = listOf("www.youtube.com", "discord.com", "proton.me"),
                            quicHosts = listOf("www.youtube.com", "discord.com"),
                        ),
                        auditCohort(
                            id = "media-messaging",
                            label = "Media and messaging",
                            domainHosts = listOf("meduza.io", "telegram.org", "signal.org"),
                            quicHosts = listOf("discord.com", "www.whatsapp.com"),
                        ),
                    ),
            )

        val selected = selectStrategyProbeTargetsForSession("session-1", intent, isManual = false)
        val selection = requireNotNull(selected.strategyProbe?.targetSelection)

        assertNotEquals("all", selection.cohortId)
        assertTrue(selection.cohortId in listOf("global-core", "media-messaging"))
        // Single cohort selected: exactly 3 domain and 2 QUIC targets
        assertEquals(3, selected.domainTargets.size)
        assertEquals(2, selected.quicTargets.size)
    }

    @Test
    fun `manual analysis with invalid cohorts falls back`() {
        val intent =
            strategyProbeIntent(
                settings = defaultDiagnosticsAppSettings(),
                baseProxyConfigJson = null,
                profileId = "automatic-audit",
                family = DiagnosticProfileFamily.AUTOMATIC_AUDIT,
                suiteId = "full_matrix_v1",
                domainTargets =
                    listOf(
                        DomainTarget(host = "fallback-a.example"),
                        DomainTarget(host = "fallback-b.example"),
                        DomainTarget(host = "fallback-c.example"),
                    ),
                quicTargets =
                    listOf(
                        QuicTarget(host = "fallback-quic-a.example"),
                        QuicTarget(host = "fallback-quic-b.example"),
                    ),
                strategyProbeTargetCohorts =
                    listOf(
                        auditCohort(
                            id = "invalid",
                            label = "Invalid",
                            domainHosts = listOf("only-one.example"),
                            quicHosts = listOf("only-one-quic.example"),
                        ),
                    ),
            )

        val selected = selectStrategyProbeTargetsForSession("audit-session", intent, isManual = true)

        assertEquals(intent.domainTargets.map(DomainTarget::host), selected.domainTargets.map(DomainTarget::host))
        assertEquals(intent.quicTargets.map(QuicTarget::host), selected.quicTargets.map(QuicTarget::host))
        assertNull(selected.strategyProbe?.targetSelection)
    }

    @Test
    fun `quick strategy probe ignores audit cohorts`() {
        val intent =
            strategyProbeIntent(
                settings = defaultDiagnosticsAppSettings(),
                baseProxyConfigJson = null,
                profileId = "automatic-probing",
                family = DiagnosticProfileFamily.AUTOMATIC_PROBING,
                suiteId = "quick_v1",
                strategyProbeTargetCohorts =
                    listOf(
                        auditCohort(
                            id = "global-core",
                            label = "Global core",
                            domainHosts = listOf("www.youtube.com", "discord.com", "proton.me"),
                            quicHosts = listOf("www.youtube.com", "discord.com"),
                        ),
                    ),
            )

        val selected = selectStrategyProbeTargetsForSession("probe-session", intent)

        assertEquals(intent.domainTargets.map(DomainTarget::host), selected.domainTargets.map(DomainTarget::host))
        assertNull(selected.strategyProbe?.targetSelection)
    }

    @Test
    fun `prepare scan preserves explicit scan origin`() =
        runTest {
            val settings = defaultDiagnosticsAppSettings()
            val intent = strategyProbeIntent(settings = settings, baseProxyConfigJson = null)
            val context = strategyProbeContext(settings = settings, preferredDnsPath = null)
            val factory =
                DiagnosticsScanRequestFactory(
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    intentResolver =
                        object : DiagnosticsIntentResolver {
                            override suspend fun resolve(
                                profileId: String,
                                pathMode: ScanPathMode,
                            ): DiagnosticsIntent = intent
                        },
                    scanContextCollector =
                        object : ScanContextCollector {
                            override suspend fun collect(intent: DiagnosticsIntent): ScanContext = context
                        },
                    diagnosticsPlanner = DefaultDiagnosticsPlanner(),
                    engineRequestEncoder = DefaultEngineRequestEncoder(),
                    json = json,
                )

            val prepared =
                factory.prepareScan(
                    profile =
                        DiagnosticProfileEntity(
                            id = "strategy-probe",
                            name = "Strategy probe",
                            source = "test",
                            version = 1,
                            requestJson =
                                diagnosticsProfileRequestJson(
                                    json = json,
                                    profileId = "strategy-probe",
                                    displayName = "Strategy probe",
                                    kind = ScanKind.STRATEGY_PROBE,
                                    family = DiagnosticProfileFamily.AUTOMATIC_PROBING,
                                    strategyProbe = StrategyProbeRequest(suiteId = "quick_v1"),
                                    allowBackground = true,
                                ),
                            updatedAt = 1L,
                        ),
                    settings = settings,
                    pathMode = ScanPathMode.RAW_PATH,
                    scanOrigin = DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND,
                    launchTrigger =
                        PolicyHandoverEvent(
                            mode = Mode.VPN,
                            previousFingerprintHash = "fingerprint-a",
                            currentFingerprintHash = "fingerprint-b",
                            classification = "transport_switch",
                            currentNetworkValidated = true,
                            currentCaptivePortalDetected = false,
                            usedRememberedPolicy = false,
                            policySignature = "baseline",
                            occurredAt = 42L,
                        ).toLaunchTrigger(),
                    exposeProgress = false,
                    registerActiveBridge = false,
                )

            assertEquals(DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND, prepared.scanOrigin)
            assertEquals(
                DiagnosticsScanLaunchOrigin.AUTOMATIC_BACKGROUND.storageValue,
                prepared.initialSession.launchOrigin,
            )
            assertEquals(DiagnosticsScanTriggerType.POLICY_HANDOVER.storageValue, prepared.initialSession.triggerType)
            assertEquals("transport_switch", prepared.initialSession.triggerClassification)
            assertEquals(42L, prepared.initialSession.triggerOccurredAt)
            assertEquals("fingerprint-a", prepared.initialSession.triggerPreviousFingerprintHash)
            assertEquals("fingerprint-b", prepared.initialSession.triggerCurrentFingerprintHash)
        }

    @Test
    fun `manual scan persists user initiated origin without trigger metadata`() =
        runTest {
            val settings = defaultDiagnosticsAppSettings()
            val intent = strategyProbeIntent(settings = settings, baseProxyConfigJson = null)
            val context = strategyProbeContext(settings = settings, preferredDnsPath = null)
            val factory =
                DiagnosticsScanRequestFactory(
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    intentResolver =
                        object : DiagnosticsIntentResolver {
                            override suspend fun resolve(
                                profileId: String,
                                pathMode: ScanPathMode,
                            ): DiagnosticsIntent = intent
                        },
                    scanContextCollector =
                        object : ScanContextCollector {
                            override suspend fun collect(intent: DiagnosticsIntent): ScanContext = context
                        },
                    diagnosticsPlanner = DefaultDiagnosticsPlanner(),
                    engineRequestEncoder = DefaultEngineRequestEncoder(),
                    json = json,
                )

            val prepared =
                factory.prepareScan(
                    profile =
                        DiagnosticProfileEntity(
                            id = "strategy-probe",
                            name = "Strategy probe",
                            source = "test",
                            version = 1,
                            requestJson =
                                diagnosticsProfileRequestJson(
                                    json = json,
                                    profileId = "strategy-probe",
                                    displayName = "Strategy probe",
                                    kind = ScanKind.STRATEGY_PROBE,
                                    family = DiagnosticProfileFamily.AUTOMATIC_PROBING,
                                    strategyProbe = StrategyProbeRequest(suiteId = "quick_v1"),
                                    allowBackground = true,
                                ),
                            updatedAt = 1L,
                        ),
                    settings = settings,
                    pathMode = ScanPathMode.RAW_PATH,
                    scanOrigin = DiagnosticsScanOrigin.USER_INITIATED,
                    exposeProgress = false,
                    registerActiveBridge = false,
                )

            assertEquals(DiagnosticsScanLaunchOrigin.USER_INITIATED.storageValue, prepared.initialSession.launchOrigin)
            assertNull(prepared.initialSession.triggerType)
            assertNull(prepared.initialSession.triggerClassification)
            assertNull(prepared.initialSession.triggerOccurredAt)
            assertNull(prepared.initialSession.triggerPreviousFingerprintHash)
            assertNull(prepared.initialSession.triggerCurrentFingerprintHash)
        }

    private suspend fun prepareStrategyProbeRequest(
        settings: com.poyka.ripdpi.proto.AppSettings,
        preferredDnsPath: EncryptedDnsPathCandidate? = null,
        baseProxyConfigJson: String? = null,
        profileId: String = "strategy-probe",
        family: DiagnosticProfileFamily = DiagnosticProfileFamily.AUTOMATIC_PROBING,
        suiteId: String = "quick_v1",
        domainTargets: List<DomainTarget> = listOf(DomainTarget(host = "example.org")),
        quicTargets: List<QuicTarget> = listOf(QuicTarget(host = "example.org")),
        strategyProbeTargetCohorts: List<StrategyProbeTargetCohortSpec> = emptyList(),
        scanOrigin: DiagnosticsScanOrigin = DiagnosticsScanOrigin.USER_INITIATED,
    ): EngineScanRequestWire {
        val intent =
            strategyProbeIntent(
                settings = settings,
                baseProxyConfigJson = baseProxyConfigJson,
                profileId = profileId,
                family = family,
                suiteId = suiteId,
                domainTargets = domainTargets,
                quicTargets = quicTargets,
                strategyProbeTargetCohorts = strategyProbeTargetCohorts,
            )
        val context = strategyProbeContext(settings = settings, preferredDnsPath = preferredDnsPath)
        val factory =
            DiagnosticsScanRequestFactory(
                networkMetadataProvider = FakeNetworkMetadataProvider(),
                intentResolver =
                    object : DiagnosticsIntentResolver {
                        override suspend fun resolve(
                            profileId: String,
                            pathMode: ScanPathMode,
                        ): DiagnosticsIntent = intent
                    },
                scanContextCollector =
                    object : ScanContextCollector {
                        override suspend fun collect(intent: DiagnosticsIntent): ScanContext = context
                    },
                diagnosticsPlanner = DefaultDiagnosticsPlanner(),
                engineRequestEncoder = DefaultEngineRequestEncoder(),
                json = json,
            )

        val prepared =
            factory.prepareScan(
                profile =
                    DiagnosticProfileEntity(
                        id = "strategy-probe",
                        name = "Strategy probe",
                        source = "test",
                        version = 1,
                        requestJson =
                            diagnosticsProfileRequestJson(
                                json = json,
                                profileId = "strategy-probe",
                                displayName = "Strategy probe",
                                kind = ScanKind.STRATEGY_PROBE,
                                family = DiagnosticProfileFamily.AUTOMATIC_PROBING,
                                strategyProbe = StrategyProbeRequest(suiteId = suiteId),
                                allowBackground = true,
                            ),
                        updatedAt = 1L,
                    ),
                settings = settings,
                pathMode = ScanPathMode.RAW_PATH,
                scanOrigin = scanOrigin,
                exposeProgress = false,
                registerActiveBridge = false,
            )

        return json.decodeFromString(EngineScanRequestWire.serializer(), prepared.requestJson)
    }

    private fun strategyProbeIntent(
        settings: com.poyka.ripdpi.proto.AppSettings,
        baseProxyConfigJson: String?,
        profileId: String = "strategy-probe",
        family: DiagnosticProfileFamily = DiagnosticProfileFamily.AUTOMATIC_PROBING,
        suiteId: String = "quick_v1",
        domainTargets: List<DomainTarget> = listOf(DomainTarget(host = "example.org")),
        quicTargets: List<QuicTarget> = listOf(QuicTarget(host = "example.org")),
        strategyProbeTargetCohorts: List<StrategyProbeTargetCohortSpec> = emptyList(),
    ) = DiagnosticsIntent(
        profileId = profileId,
        displayName = "Strategy probe",
        settings = settings,
        kind = ScanKind.STRATEGY_PROBE,
        family = family,
        regionTag = null,
        executionPolicy =
            ExecutionPolicy(
                manualOnly = false,
                allowBackground = false,
                requiresRawPath = true,
                probePersistencePolicy = ProbePersistencePolicy.MANUAL_ONLY,
            ),
        packRefs = emptyList(),
        domainTargets = domainTargets,
        dnsTargets = emptyList(),
        tcpTargets = emptyList(),
        quicTargets = quicTargets,
        serviceTargets = emptyList(),
        circumventionTargets = emptyList(),
        throughputTargets = emptyList(),
        whitelistSni = emptyList(),
        telegramTarget = null,
        strategyProbe = StrategyProbeRequest(suiteId = suiteId, baseProxyConfigJson = baseProxyConfigJson),
        strategyProbeTargetCohorts = strategyProbeTargetCohorts,
        requestedPathMode = ScanPathMode.RAW_PATH,
    )

    private fun strategyProbeContext(
        settings: com.poyka.ripdpi.proto.AppSettings,
        preferredDnsPath: EncryptedDnsPathCandidate?,
    ): ScanContext {
        val contextSnapshot = contextProvider.captureContextForTest()
        return ScanContext(
            settings = settings,
            pathMode = ScanPathMode.RAW_PATH,
            networkFingerprint = null,
            preferredDnsPath = preferredDnsPath,
            networkSnapshot = null,
            serviceMode = Mode.VPN.name,
            contextSnapshot = contextSnapshot,
            approachSnapshot = createStoredApproachSnapshot(json, settings, null, contextSnapshot),
        )
    }

    private fun decodeRuntimeDns(request: EngineScanRequestWire) =
        requireNotNull(
            requireNotNull(
                decodeRipDpiProxyUiPreferences(requireNotNull(request.strategyProbe?.baseProxyConfigJson)),
            ).runtimeContext?.encryptedDns,
        )

    private fun auditCohort(
        id: String,
        label: String,
        domainHosts: List<String>,
        quicHosts: List<String>,
    ) = StrategyProbeTargetCohortSpec(
        id = id,
        label = label,
        domainTargets = domainHosts.map { host -> DomainTarget(host = host) },
        quicTargets = quicHosts.map { host -> QuicTarget(host = host) },
    )
}
