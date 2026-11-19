import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsCatalogQuicVersionTest {
    @Test
    fun `QUIC version matrix is a manual raw path profile without bundled targets`() {
        val profiles =
            DefaultDiagnosticsCatalogProfileSource.load(
                DiagnosticsCatalogIndex(DefaultDiagnosticsCatalogPackSource.load()),
            )
        val profile = profiles.single { it.id == "quic-version-matrix" }

        assertTrue(profile.executionPolicy.manualOnly)
        assertFalse(profile.executionPolicy.allowBackground)
        assertTrue(profile.executionPolicy.requiresRawPath)
        assertEquals(CatalogScanKind.CONNECTIVITY, profile.kind)
        assertTrue(profile.domainTargets.isEmpty() && profile.tcpTargets.isEmpty() && profile.whitelistSni.isEmpty())
        assertEquals(QuicVersionMatrixDefinition(), profile.quicVersionMatrix)
        assertTrue(profiles.filterNot { it.id == profile.id }.all { it.quicVersionMatrix == null })

        val rendered =
            Json
                .parseToJsonElement(DiagnosticsCatalogDefinitions.renderCatalog())
                .jsonObject
                .getValue("profiles")
                .jsonArray
                .single {
                    it.jsonObject
                        .getValue("id")
                        .jsonPrimitive.content == "quic-version-matrix"
                }.jsonObject
                .getValue("request")
                .jsonObject
                .getValue("quicVersionMatrix")
                .jsonObject
        assertEquals(setOf("host", "controlSni", "testSni"), rendered.keys)
        assertTrue(rendered.values.all { it.jsonPrimitive.content.isEmpty() })

        assertFailsWith<IllegalArgumentException> {
            profile
                .copy(
                    quicVersionMatrix = QuicVersionMatrixDefinition(host = "203.0.113.7"),
                ).validateQuicVersionMatrix()
        }
    }
}
