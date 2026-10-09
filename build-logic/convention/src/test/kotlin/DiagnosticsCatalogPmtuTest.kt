import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DiagnosticsCatalogPmtuTest {
    @Test
    fun `PMTU is an isolated manual raw path profile with one bounded control`() {
        val profiles =
            DefaultDiagnosticsCatalogProfileSource.load(
                DiagnosticsCatalogIndex(DefaultDiagnosticsCatalogPackSource.load()),
            )
        val profile = profiles.single { it.id == "pmtu-connectivity" }
        val config = assertNotNull(profile.pmtuProbe)

        assertTrue(profile.executionPolicy.manualOnly)
        assertFalse(profile.executionPolicy.allowBackground)
        assertTrue(profile.executionPolicy.requiresRawPath)
        assertEquals(CatalogProbePersistencePolicy.MANUAL_ONLY, profile.executionPolicy.probePersistencePolicy)
        assertEquals(CatalogDiagnosticProfileFamily.GENERAL, profile.family)
        assertEquals(CatalogScanKind.CONNECTIVITY, profile.kind)
        assertTrue(profile.domainTargets.isEmpty())
        assertTrue(profile.dnsTargets.isEmpty())
        assertTrue(profile.tcpTargets.isEmpty())
        assertTrue(profile.quicTargets.isEmpty())
        assertTrue(profile.serviceTargets.isEmpty())
        assertTrue(profile.circumventionTargets.isEmpty())
        assertTrue(profile.throughputTargets.isEmpty())
        assertTrue(profile.whitelistSni.isEmpty())
        assertEquals(null, profile.telegramTarget)
        assertEquals(null, profile.strategyProbe)
        assertEquals(null, profile.selectiveMatrix)
        assertEquals(null, profile.ipFamilyProbe)
        assertTrue(profiles.filterNot { it.id == profile.id }.all { it.pmtuProbe == null })
        assertEquals(PmtuProbeDefinition(), config)
        profile.validatePmtuProbe()
    }

    @Test
    fun `renderer includes exact PMTU control config and omits absent optional fields`() {
        val profiles =
            Json
                .parseToJsonElement(DiagnosticsCatalogDefinitions.renderCatalog())
                .jsonObject
                .getValue("profiles")
                .jsonArray
        val profile =
            profiles.single {
                it.jsonObject
                    .getValue("id")
                    .jsonPrimitive.content == "pmtu-connectivity"
            }
        val config =
            profile.jsonObject
                .getValue("request")
                .jsonObject
                .getValue("pmtuProbe")
                .jsonObject
        assertEquals(
            Json.parseToJsonElement(
                """{"version":1,"host":"www.cloudflare.com","port":443,
                    "timeoutMs":10000,"observationMs":3000,"upperBoundUdpPayloadBytes":1472}""",
            ),
            config,
        )
        assertFalse(config.containsKey("connectIp"))
        profiles.filterNot { it == profile }.forEach {
            assertFalse(
                it.jsonObject
                    .getValue("request")
                    .jsonObject
                    .containsKey("pmtuProbe"),
            )
        }
    }

    @Test
    fun `validator rejects automatic proxy and unreviewed PMTU controls`() {
        val profile = pmtuConnectivityProfile()
        val config = assertNotNull(profile.pmtuProbe)
        val invalidProfiles =
            listOf(
                profile.copy(
                    executionPolicy = policy(manualOnly = false, allowBackground = false, requiresRawPath = true),
                ),
                profile.copy(
                    executionPolicy = policy(manualOnly = true, allowBackground = true, requiresRawPath = true),
                ),
                profile.copy(
                    executionPolicy = policy(manualOnly = true, allowBackground = false, requiresRawPath = false),
                ),
                profile.copy(pmtuProbe = config.copy(version = 2)),
                profile.copy(pmtuProbe = config.copy(host = "example.com")),
                profile.copy(pmtuProbe = config.copy(port = 8443)),
                profile.copy(pmtuProbe = config.copy(observationMs = 20_000)),
                profile.copy(pmtuProbe = config.copy(upperBoundUdpPayloadBytes = 1_200)),
                profile.copy(pmtuProbe = config.copy(timeoutMs = 15_001)),
                profile.copy(pmtuProbe = config.copy(upperBoundUdpPayloadBytes = 1_473)),
            )
        invalidProfiles.forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { invalid.validatePmtuProbe() }
        }
        assertFailsWith<IllegalArgumentException> {
            DiagnosticsCatalogValidator().validate(DiagnosticsCatalog(emptyList(), invalidProfiles.take(1)))
        }
    }
}
