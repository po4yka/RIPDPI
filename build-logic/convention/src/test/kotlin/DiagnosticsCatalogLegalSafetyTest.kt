import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
        assertEquals(3, messagingProfile.version)
        messagingProfile.serviceTargets.forEach { target ->
            assertNull(target.bootstrapUrl, "Profile target ${target.id} should not ship a bootstrap URL")
            assertNotNull(target.tcpEndpointHost, "Profile target ${target.id} still needs a TCP endpoint host")
        }

        val throttlingProfile = profiles.single { it.id == "ru-throttling" }
        throttlingProfile.throughputTargets.forEach { target ->
            assertContains(target.url, "https://")
        }
    }
}
