import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsCatalogTcpControlsTest {
    @Test
    fun `bundled fat header profiles send named TLS and preserve plaintext controls`() {
        val profiles =
            Json
                .parseToJsonElement(DiagnosticsCatalogDefinitions.renderCatalog())
                .jsonObject
                .getValue("profiles")
                .jsonArray
                .map { it.jsonObject }
                .associateBy { it.getValue("id").jsonPrimitive.content }

        for (profileId in listOf("default", "dpi-detector-full", "ru-web-connectivity", "ru-dpi-full")) {
            val request = profiles.getValue(profileId).getValue("request").jsonObject
            val targets = request.getValue("tcpTargets").jsonArray.map { it.jsonObject }
            val tlsTargets = targets.filter { it.getValue("port").jsonPrimitive.content == "443" }
            assertEquals(
                mapOf("1.1.1.1" to "cloudflare-dns.com", "8.8.8.8" to "dns.google"),
                tlsTargets.associate {
                    it.getValue("ip").jsonPrimitive.content to it.getValue("sni").jsonPrimitive.content
                },
                profileId,
            )
            assertTrue(request.getValue("whitelistSni").jsonArray.isNotEmpty(), profileId)
            val plainTargets = targets - tlsTargets.toSet()
            assertTrue(
                plainTargets.all { it.getValue("port").jsonPrimitive.content == "80" && "sni" !in it },
                profileId,
            )
            assertEquals(if (profileId == "dpi-detector-full") 6 else 0, plainTargets.size, profileId)
        }
    }
}
