import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsCatalogTlsHopTest {
    @Test
    fun `TLS hop comparison is a manual raw path profile without bundled targets`() {
        val profiles =
            DefaultDiagnosticsCatalogProfileSource.load(
                DiagnosticsCatalogIndex(DefaultDiagnosticsCatalogPackSource.load()),
            )
        val profile = profiles.single { it.id == "tls-hop-comparison" }

        assertTrue(profile.executionPolicy.manualOnly)
        assertFalse(profile.executionPolicy.allowBackground)
        assertTrue(profile.executionPolicy.requiresRawPath)
        assertEquals(CatalogScanKind.CONNECTIVITY, profile.kind)
        assertTrue(profile.domainTargets.isEmpty() && profile.tcpTargets.isEmpty() && profile.whitelistSni.isEmpty())
        assertEquals(TlsHopComparisonDefinition(), profile.tlsHopComparison)
        assertTrue(profiles.filterNot { it.id == profile.id }.all { it.tlsHopComparison == null })

        val rendered =
            Json
                .parseToJsonElement(DiagnosticsCatalogDefinitions.renderCatalog())
                .jsonObject
                .getValue("profiles")
                .jsonArray
                .single {
                    it.jsonObject
                        .getValue("id")
                        .jsonPrimitive.content == "tls-hop-comparison"
                }.jsonObject
                .getValue("request")
                .jsonObject
                .getValue("tlsHopComparison")
                .jsonObject
        assertEquals(setOf("host", "controlSni", "testSni"), rendered.keys)
        assertTrue(rendered.values.all { it.jsonPrimitive.content.isEmpty() })

        assertFailsWith<IllegalArgumentException> {
            profile.copy(tlsHopComparison = TlsHopComparisonDefinition(host = "203.0.113.7")).validateTlsHopComparison()
        }
    }
}
