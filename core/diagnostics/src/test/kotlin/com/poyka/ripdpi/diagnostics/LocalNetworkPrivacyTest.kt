package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRedactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkPrivacyTest {
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    @Test
    fun `legacy contexts retain omitted local evidence despite encode defaults`() {
        val legacy = FakeDiagnosticsContextProvider().captureContextForTest()
        val encoded = json.encodeToString(DiagnosticContextModel.serializer(), legacy)
        assertFalse(encoded.contains("localNetwork"))
        assertNull(json.decodeFromString(DiagnosticContextModel.serializer(), encoded).localNetwork)
        val redacted = json.encodeToString(RedactedDiagnosticContextSummary.serializer(), legacy.toRedactedSummary())
        assertFalse(redacted.contains("localNetwork"))
    }

    @Test
    fun `new evidence round trips through context persistence and redacted export`() {
        val local =
            LocalNetworkContextModel(
                capturedAt = 100,
                transport = LocalTransport.VPN,
                sim = LocalSimConstraints(scope = LocalSimScope.DEFAULT_DATA, simState = LocalSimState.PIN_REQUIRED),
            )
        val original = FakeDiagnosticsContextProvider().captureContextForTest().copy(localNetwork = local)
        val stored =
            json.decodeFromString(
                DiagnosticContextModel.serializer(),
                json.encodeToString(DiagnosticContextModel.serializer(), original),
            )
        assertEquals(local, stored.localNetwork)
        val archived = DiagnosticsArchiveRedactor(json).redact(stored)
        assertEquals(local, archived.localNetwork)
        assertEquals(local, archived.toRedactedSummary().localNetwork)
        val lines =
            DiagnosticsSummaryProjector()
                .project(
                    null,
                    null,
                    null,
                    archived,
                    null,
                    emptyList(),
                    emptyList(),
                ).environment.lines
        assertTrue(lines.contains("localNetwork.sim.state=PIN_REQUIRED"))
        assertTrue(lines.contains("localNetwork.causeValidated=false"))
        assertTrue(lines.contains("localNetwork.providerPolicy=unverified"))
    }

    @Test
    fun `scope loss discards stale SIM facts and invalid timestamps at export`() {
        val local =
            LocalNetworkContextModel(
                capturedAt = Long.MAX_VALUE,
                sim = LocalSimConstraints(scope = LocalSimScope.CHANGED, simState = LocalSimState.PIN_REQUIRED),
            )
        val context = FakeDiagnosticsContextProvider().captureContextForTest().copy(localNetwork = local)
        val redacted = requireNotNull(DiagnosticsArchiveRedactor(json).redact(context).localNetwork)
        assertEquals(0L, redacted.capturedAt)
        assertEquals(LocalSimState.UNKNOWN, redacted.sim.simState)
        assertFalse(redacted.localNetworkSummaryLines().any { it.contains("PIN_REQUIRED") })
        assertEquals(redacted, redacted.toSafeLocalNetworkContext())
    }

    @Test
    fun `unknown subscriber fields never survive model decoding and reencoding`() {
        val input =
            """
            {"capturedAt":1,"imsi":"subscriber-canary","sim":{"scope":"default_data",
            "subscriptionId":12345,"iccid":"card-canary","phoneNumber":"phone-canary"}}
            """.trimIndent()
        val decoded = json.decodeFromString(LocalNetworkContextModel.serializer(), input)
        val encoded = json.encodeToString(LocalNetworkContextModel.serializer(), decoded.toSafeLocalNetworkContext())
        assertFalse(encoded.contains("canary"))
        assertFalse(encoded.contains("12345"))
        val root = json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("capturedAt", "transport", "device", "sim"), root.keys)
        assertEquals(
            setOf(
                "scope",
                "activeDataMatchesDefault",
                "simState",
                "mobileDataEnabled",
                "dataConnectionAllowed",
                "roaming",
                "roamingEnabled",
                "voiceServiceState",
                "dataState",
            ),
            root.getValue("sim").jsonObject.keys,
        )
    }
}
