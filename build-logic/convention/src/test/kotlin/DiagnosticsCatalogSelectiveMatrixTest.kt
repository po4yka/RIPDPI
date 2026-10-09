import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DiagnosticsCatalogSelectiveMatrixTest {
    @Test
    fun `matrix is an isolated manual profile with independent dated cohorts`() {
        val profiles =
            DefaultDiagnosticsCatalogProfileSource.load(
                DiagnosticsCatalogIndex(DefaultDiagnosticsCatalogPackSource.load()),
            )
        val profile = profiles.single { it.id == "selective-availability-matrix" }
        val matrix = assertNotNull(profile.selectiveMatrix)

        assertTrue(profile.executionPolicy.manualOnly)
        assertFalse(profile.executionPolicy.allowBackground)
        assertEquals(CatalogProbePersistencePolicy.MANUAL_ONLY, profile.executionPolicy.probePersistencePolicy)
        assertEquals(CatalogScanKind.CONNECTIVITY, profile.kind)
        assertTrue(profile.domainTargets.isEmpty())
        assertTrue(profile.dnsTargets.isEmpty())
        assertTrue(profile.tcpTargets.isEmpty())
        assertTrue(profile.quicTargets.isEmpty())
        assertTrue(profile.whitelistSni.isEmpty())
        assertTrue(profiles.filterNot { it.id == profile.id }.all { it.selectiveMatrix == null })
        assertEquals(1, matrix.version)
        assertEquals(2, matrix.repetitions)
        assertEquals(5_000L, matrix.timeoutMs)
        assertEquals(65_536, matrix.maxResponseBytes)
        assertEquals(6, matrix.targets.size)
        assertEquals(setOf("declared_available", "domestic", "global"), matrix.targets.map { it.cohort }.toSet())
        matrix.targets.groupBy { it.cohort }.values.forEach { targets ->
            assertEquals(2, targets.map { it.infrastructureGroup }.distinct().size)
        }
        matrix.targets.forEach { target ->
            assertTrue(target.url.endsWith("/robots.txt"))
            assertTrue(target.sourceUrl.startsWith("https://"))
            assertNotNull(target.lastVerifiedAt)
            if (target.cohort == "declared_available") {
                assertEquals("https://t.me/mintsifry/2603", target.sourceUrl)
                assertEquals("2025-09-05", target.sourceDate)
            }
        }
        profile.validateSelectiveMatrix()
    }

    @Test
    fun `renderer includes config provenance and optional verification timestamp only for matrix profile`() {
        val root = Json.parseToJsonElement(DiagnosticsCatalogDefinitions.renderCatalog()).jsonObject
        val profiles = root.getValue("profiles").jsonArray
        val matrixProfile =
            profiles.single {
                it.jsonObject
                    .getValue("id")
                    .jsonPrimitive.content == "selective-availability-matrix"
            }
        val request = matrixProfile.jsonObject.getValue("request").jsonObject
        val matrix = request.getValue("selectiveMatrix").jsonObject
        assertEquals("1", matrix.getValue("version").jsonPrimitive.content)
        assertEquals("ru-selective-2026-10-09", matrix.getValue("catalogVersion").jsonPrimitive.content)
        assertEquals(6, matrix.getValue("targets").jsonArray.size)
        profiles.filterNot { it == matrixProfile }.forEach { profile ->
            assertFalse(
                profile.jsonObject
                    .getValue("request")
                    .jsonObject
                    .containsKey("selectiveMatrix"),
            )
        }
    }

    @Test
    fun `validator rejects automatic execution malformed endpoints and excessive response budgets`() {
        val profile = selectiveAvailabilityMatrixProfile()
        val matrix = assertNotNull(profile.selectiveMatrix)

        fun withUrl(url: String): DiagnosticsProfileDefinition =
            profile.copy(selectiveMatrix = matrix.copy(targets = listOf(matrix.targets.first().copy(url = url))))

        val invalidProfiles =
            listOf(
                profile.copy(
                    executionPolicy = policy(manualOnly = false, allowBackground = true, requiresRawPath = false),
                ),
                profile.copy(selectiveMatrix = matrix.copy(repetitions = 4)),
                profile.copy(selectiveMatrix = matrix.copy(timeoutMs = 10_001)),
                profile.copy(selectiveMatrix = matrix.copy(timeoutMs = 99)),
                profile.copy(selectiveMatrix = matrix.copy(maxResponseBytes = 65_537)),
                profile.copy(selectiveMatrix = matrix.copy(targets = matrix.targets + matrix.targets)),
                withUrl("http://example.com/"),
                withUrl("https://user@example.com/"),
                withUrl("https://example.com/?q=x"),
            )
        invalidProfiles.forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { invalid.validateSelectiveMatrix() }
        }
    }

    @Test
    fun `registry checks matrix urls and validator rejects denylisted targets`() {
        val registry =
            DiagnosticsCatalogLegalSafetyRegistry(
                rules =
                    listOf(
                        DiagnosticsCatalogLegalSafetyRule(
                            domain = "example.com",
                            classification = CatalogLegalSafety.UNSAFE,
                            shippingPolicy = CatalogLegalSafetyShippingPolicy.DENYLIST,
                            jurisdictionTag = "test",
                            ruleId = "test_matrix_denied",
                        ),
                    ),
            )
        val profile = selectiveAvailabilityMatrixProfile()
        val matrix = assertNotNull(profile.selectiveMatrix)
        val unsafeProfile =
            profile.copy(
                selectiveMatrix =
                    matrix.copy(targets = listOf(matrix.targets.first().copy(url = "https://example.com/robots.txt"))),
            )
        val catalog = registry.annotate(DiagnosticsCatalog(emptyList(), listOf(unsafeProfile)))
        val metadata =
            assertNotNull(
                catalog.profiles
                    .single()
                    .selectiveMatrix
                    ?.targets
                    ?.single()
                    ?.legalSafetyMetadata,
            )
        assertEquals(CatalogLegalSafety.UNSAFE, metadata.classification)
        assertEquals(CatalogLegalSafetyShippingPolicy.DENYLIST, metadata.shippingPolicy)
        assertContains(DiagnosticsCatalogJsonRenderer().render(catalog), "test_matrix_denied")
        val error =
            assertFailsWith<IllegalArgumentException> { DiagnosticsCatalogValidator(registry).validate(catalog) }
        assertContains(error.message.orEmpty(), "unsafe targets")
    }
}
