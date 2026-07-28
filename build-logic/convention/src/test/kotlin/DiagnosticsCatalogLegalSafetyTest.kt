import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticsCatalogLegalSafetyTest {
    private val registry =
        DiagnosticsCatalogLegalSafetyRegistry(
            rules =
                listOf(
                    DiagnosticsCatalogLegalSafetyRule(
                        domain = "rutor.info",
                        classification = CatalogLegalSafety.UNSAFE,
                        shippingPolicy = CatalogLegalSafetyShippingPolicy.DENYLIST,
                        jurisdictionTag = "ru",
                        ruleId = "ru_client_risk_rutor",
                    ),
                ),
        )

    @Test
    fun `renderer includes machine readable legal safety metadata`() {
        val assembler =
            DiagnosticsCatalogAssembler(
                packSource = DiagnosticsCatalogPackSource { emptyList() },
                profileSource =
                    DiagnosticsCatalogProfileSource {
                        listOf(
                            DiagnosticsProfileDefinition(
                                id = "manual-check",
                                name = "Manual check",
                                intentBucket = CatalogProfileIntentBucket.MANUAL_SENSITIVE,
                                legalSafety = CatalogLegalSafety.UNSAFE,
                                executionPolicy =
                                    policy(
                                        manualOnly = true,
                                        allowBackground = false,
                                        requiresRawPath = false,
                                    ),
                                dnsTargets = listOf(DnsTargetDefinition(domain = "rutor.info")),
                            ),
                        )
                    },
                legalSafetyRegistry = registry,
                validator = DiagnosticsCatalogValidator(legalSafetyRegistry = registry),
                renderer = DiagnosticsCatalogJsonRenderer(),
            )

        val rendered = assembler.renderCatalog()

        assertContains(rendered, """"classification": "UNSAFE"""")
        assertContains(rendered, """"shippingPolicy": "DENYLIST"""")
        assertContains(rendered, """"ruleId": "ru_client_risk_rutor"""")
    }

    @Test
    fun `validator rejects unsafe targets in safe default profiles`() {
        val assembler =
            DiagnosticsCatalogAssembler(
                packSource = DiagnosticsCatalogPackSource { emptyList() },
                profileSource =
                    DiagnosticsCatalogProfileSource {
                        listOf(
                            DiagnosticsProfileDefinition(
                                id = "default",
                                name = "Default diagnostics",
                                intentBucket = CatalogProfileIntentBucket.SAFE_DEFAULT,
                                legalSafety = CatalogLegalSafety.SAFE,
                                executionPolicy =
                                    policy(
                                        manualOnly = false,
                                        allowBackground = false,
                                        requiresRawPath = false,
                                    ),
                                dnsTargets = listOf(DnsTargetDefinition(domain = "rutor.info")),
                            ),
                        )
                    },
                legalSafetyRegistry = registry,
                validator = DiagnosticsCatalogValidator(legalSafetyRegistry = registry),
                renderer = DiagnosticsCatalogJsonRenderer(),
            )

        val error = assertFailsWith<IllegalArgumentException> { assembler.renderCatalog() }

        assertContains(error.message.orEmpty(), "contains unsafe targets")
        assertContains(error.message.orEmpty(), "rutor.info")
    }

    @Test
    fun `artifact guard rejects denylisted domains in committed generated fixtures`() {
        val repoRoot = Files.createTempDirectory("diagnostics-catalog-guard")
        val fixtureDirectory = repoRoot.resolve("diagnostics-contract-fixtures")
        Files.createDirectories(fixtureDirectory)
        Files.writeString(
            fixtureDirectory.resolve("profile_catalog_current.json"),
            """{"profiles":[{"domain":"rutor.info"}]}""",
        )

        val assembler =
            DiagnosticsCatalogAssembler(
                packSource = DiagnosticsCatalogPackSource { emptyList() },
                profileSource =
                    DiagnosticsCatalogProfileSource {
                        listOf(
                            DiagnosticsProfileDefinition(
                                id = "default",
                                name = "Default diagnostics",
                                intentBucket = CatalogProfileIntentBucket.SAFE_DEFAULT,
                                legalSafety = CatalogLegalSafety.SAFE,
                                executionPolicy =
                                    policy(
                                        manualOnly = false,
                                        allowBackground = false,
                                        requiresRawPath = false,
                                    ),
                                domainTargets = listOf(DomainTargetDefinition(host = "example.org")),
                            ),
                        )
                    },
                legalSafetyRegistry = registry,
                validator =
                    DiagnosticsCatalogValidator(
                        legalSafetyRegistry = registry,
                        repoRoot = repoRoot,
                        generatedArtifactDirectories = listOf("diagnostics-contract-fixtures"),
                        enforceGeneratedArtifactGuard = true,
                    ),
                renderer = DiagnosticsCatalogJsonRenderer(),
            )

        val error = assertFailsWith<IllegalArgumentException> { assembler.renderCatalog() }

        assertContains(error.message.orEmpty(), "diagnostics-contract-fixtures/profile_catalog_current.json")
        assertContains(error.message.orEmpty(), "rutor.info")
    }

    @Test
    fun `sensitive messaging services omit shipped bootstrap urls while throughput checks keep required urls`() {
        val index = DiagnosticsCatalogIndex(DefaultDiagnosticsCatalogPackSource.load())
        val profiles = DefaultDiagnosticsCatalogProfileSource.load(index)

        val messagingPack = requireNotNull(index["ru-messaging"])
        assertEquals(2, messagingPack.version)
        messagingPack.serviceTargets.forEach { target ->
            assertNull(target.bootstrapUrl, "Service target ${target.id} should not ship a bootstrap URL")
            assertNotNull(target.tcpEndpointHost, "Service target ${target.id} still needs a TCP endpoint host")
        }

        val messagingProfile = profiles.single { it.id == "ru-messaging" }
        assertEquals(4, messagingProfile.version)
        messagingProfile.serviceTargets.forEach { target ->
            assertNull(target.bootstrapUrl, "Profile target ${target.id} should not ship a bootstrap URL")
            assertNotNull(target.tcpEndpointHost, "Profile target ${target.id} still needs a TCP endpoint host")
        }

        val throttlingProfile = profiles.single { it.id == "ru-throttling" }
        throttlingProfile.throughputTargets.forEach { target ->
            assertContains(target.url, "https://")
        }
    }

    @Test
    fun `full dpi profile includes telegram availability audit target`() {
        val index = DiagnosticsCatalogIndex(DefaultDiagnosticsCatalogPackSource.load())
        val profiles = DefaultDiagnosticsCatalogProfileSource.load(index)

        val fullProfile = profiles.single { it.id == "ru-dpi-full" }
        val telegramTarget = requireNotNull(fullProfile.telegramTarget)

        assertEquals(7, fullProfile.version)
        assertEquals("https://telegram.org/img/Telegram200million.png", telegramTarget.mediaUrl)
        assertEquals(5, telegramTarget.dcEndpoints.size)
        assertEquals(listOf("DC1", "DC2", "DC3", "DC4", "DC5"), telegramTarget.dcEndpoints.map { it.label })
    }

    @Test
    fun `composite-only profiles preserve catalog execution contracts`() {
        val index = DiagnosticsCatalogIndex(DefaultDiagnosticsCatalogPackSource.load())
        val profiles = DefaultDiagnosticsCatalogProfileSource.load(index)

        val pathComparison = profiles.single { it.id == "path-comparison" }
        assertEquals(CatalogScanKind.CONNECTIVITY, pathComparison.kind)
        assertEquals(CatalogProfileIntentBucket.MANUAL_SENSITIVE, pathComparison.intentBucket)
        assertTrue(pathComparison.executionPolicy.manualOnly)
        assertTrue(!pathComparison.executionPolicy.allowBackground)
        assertTrue(!pathComparison.executionPolicy.requiresRawPath)
        assertTrue(pathComparison.domainTargets.isEmpty())

        val strategy = profiles.single { it.id == "ru-dpi-strategy" }
        assertEquals(CatalogScanKind.STRATEGY_PROBE, strategy.kind)
        assertEquals(CatalogProfileIntentBucket.MANUAL_SENSITIVE, strategy.intentBucket)
        assertEquals(CatalogLegalSafety.SENSITIVE, strategy.legalSafety)
        assertEquals("ru", strategy.regionTag)
        assertTrue(strategy.executionPolicy.manualOnly)
        assertTrue(!strategy.executionPolicy.allowBackground)
        assertTrue(strategy.executionPolicy.requiresRawPath)
        assertEquals("full_matrix_v1", strategy.strategyProbe?.suiteId)
        assertEquals(
            listOf(
                "ru-independent-media@1",
                "ru-global-platforms@1",
                "ru-messaging@2",
                "ru-circumvention@1",
                "neutral-control@2",
            ),
            strategy.packRefs,
        )
        assertEquals(
            setOf(
                "cloudflare.com",
                "www.google.com",
                "www.youtube.com",
                "discord.com",
                "proton.me",
                "telegram.org",
                "signal.org",
                "www.whatsapp.com",
                "speed.cloudflare.com",
                "proof.ovh.net",
            ),
            strategy.domainTargets.mapTo(mutableSetOf()) { it.host },
        )
        assertEquals(
            setOf("www.youtube.com", "discord.com", "www.whatsapp.com"),
            strategy.quicTargets.mapTo(mutableSetOf()) { it.host },
        )
    }

    @Test
    fun `control evidence is preserved in profiles and rendered catalog`() {
        val index = DiagnosticsCatalogIndex(DefaultDiagnosticsCatalogPackSource.load())
        val profiles = DefaultDiagnosticsCatalogProfileSource.load(index)

        listOf("automatic-probing", "automatic-audit", "ru-dpi-full", "ru-dpi-strategy").forEach { profileId ->
            val profile = profiles.single { it.id == profileId }
            assertTrue(
                profile.domainTargets.any { it.isControl },
                "Profile $profileId must carry at least one neutral control domain",
            )
        }

        listOf("automatic-probing", "automatic-audit").forEach { profileId ->
            val profile = profiles.single { it.id == profileId }
            val concurrencyTargets = profile.domainTargets.filterNot { it.isControl }
            assertTrue(concurrencyTargets.isNotEmpty())
            assertTrue(
                concurrencyTargets.all { target ->
                    target.concurrencyProbe ==
                        ConcurrencyProbeTargetMetadataDefinition(
                            cohortId = "global-platform-control-v1",
                            maxParallelism = 8,
                        )
                },
            )
        }

        val rendered = DiagnosticsCatalogDefinitions.renderCatalog()
        assertContains(rendered, "\"isControl\": true")
        assertContains(rendered, "\"concurrencyProbe\"")
    }

    @Test
    fun `default diagnostics stays lightweight and resolver audit keeps full matrix expansion`() {
        val index = DiagnosticsCatalogIndex(DefaultDiagnosticsCatalogPackSource.load())
        val profiles = DefaultDiagnosticsCatalogProfileSource.load(index)

        val defaultProfile = profiles.single { it.id == "default" }
        val resolverAuditProfile = profiles.single { it.id == "resolver-audit" }

        assertEquals(4, defaultProfile.version)
        assertEquals(listOf("cloudflare.com", "google.com", "youtube.com"), defaultProfile.dnsTargets.map { it.domain })
        assertTrue(defaultProfile.dnsTargets.all { it.udpServer == null })

        assertTrue(resolverAuditProfile.executionPolicy.manualOnly)
        assertEquals(
            listOf("cloudflare.com", "google.com", "youtube.com"),
            resolverAuditProfile.dnsTargets.map { it.domain },
        )
        assertTrue(resolverAuditProfile.dnsTargets.all { it.udpServer == null })
    }
}
