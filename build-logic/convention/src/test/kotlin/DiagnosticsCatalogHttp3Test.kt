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

class DiagnosticsCatalogHttp3Test {
    @Test
    fun `HTTP3 is an isolated manual raw path profile with one bounded control`() {
        val profiles =
            DefaultDiagnosticsCatalogProfileSource.load(
                DiagnosticsCatalogIndex(DefaultDiagnosticsCatalogPackSource.load()),
            )
        val profile = profiles.single { it.id == "http3-connectivity" }
        val config = assertNotNull(profile.http3Probe)

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
        assertTrue(profiles.filterNot { it.id == profile.id }.all { it.http3Probe == null })
        assertEquals(Http3ProbeDefinition(), config)
        profile.validateHttp3Probe()
    }

    @Test
    fun `renderer includes exact HTTP3 control config and omits absent optional fields`() {
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
                    .jsonPrimitive.content == "http3-connectivity"
            }
        val config =
            profile.jsonObject
                .getValue("request")
                .jsonObject
                .getValue("http3Probe")
                .jsonObject
        assertEquals(
            Json.parseToJsonElement(
                """{"version":1,"host":"www.cloudflare.com","port":443,"path":"/cdn-cgi/trace",
                    "timeoutMs":5000,"maxResponseBytes":65536}""",
            ),
            config,
        )
        assertFalse(config.containsKey("connectIp"))
        profiles.filterNot { it == profile }.forEach {
            assertFalse(
                it.jsonObject
                    .getValue("request")
                    .jsonObject
                    .containsKey("http3Probe"),
            )
        }
    }

    @Test
    fun `validator rejects automatic proxy and unreviewed HTTP3 controls`() {
        val profile = http3ConnectivityProfile()
        val config = assertNotNull(profile.http3Probe)
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
                profile.copy(http3Probe = config.copy(version = 2)),
                profile.copy(http3Probe = config.copy(host = "example.com")),
                profile.copy(http3Probe = config.copy(port = 8443)),
                profile.copy(http3Probe = config.copy(path = "/")),
                profile.copy(http3Probe = config.copy(connectIp = "1.1.1.1")),
                profile.copy(http3Probe = config.copy(timeoutMs = 15_001)),
                profile.copy(http3Probe = config.copy(maxResponseBytes = 262_145)),
            )
        invalidProfiles.forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { invalid.validateHttp3Probe() }
        }
        assertFailsWith<IllegalArgumentException> {
            DiagnosticsCatalogValidator().validate(DiagnosticsCatalog(emptyList(), invalidProfiles.take(1)))
        }
    }
}
