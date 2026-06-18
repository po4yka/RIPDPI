package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.subscription.SingBoxParseResult
import com.poyka.ripdpi.data.subscription.SingBoxSubscriptionParser
import com.poyka.ripdpi.data.wireguard.AmneziaWgParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Client half of the cross-repo bundle contract test. The server repo
 * (ripdpi-vpn-deploy) emits the `ripdpi` object and runs
 * `tests/unit/test_bundle_schema.py` against `contract/ripdpi-bundle.schema.json`.
 * This test validates the parse side against a byte-identical vendored copy of
 * the same schema and the same cohort-fingerprint golden (see
 * `core/data/src/test/resources/contract/README.md`), so the contract cannot
 * drift silently between the two repos.
 */
class RipdpiBundleContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(path: String): String =
        requireNotNull(javaClass.getResourceAsStream(path)) { "missing test resource: $path" }
            .bufferedReader()
            .use { it.readText() }

    private val schema: JsonObject get() = json.parseToJsonElement(resource(SCHEMA)).jsonObject
    private val example: JsonObject get() = json.parseToJsonElement(resource(EXAMPLE)).jsonObject
    private val golden: JsonObject get() = json.parseToJsonElement(resource(GOLDEN)).jsonObject

    // -------------------------------------------------------------------------
    // Cross-repo drift pin: the vendored schema's contract version must equal
    // the version this parser supports. A stale vendored copy (or a parser that
    // has not learned a bumped version) fails here.
    // -------------------------------------------------------------------------
    @Test
    fun `schema contract version matches the parser supported version`() {
        val schemaVersion = schema["x-contract-version"]!!.jsonPrimitive.int
        assertEquals(SingBoxSubscriptionParser.RIPDPI_SCHEMA_VERSION, schemaVersion)
        val constVersion =
            schema["properties"]!!
                .jsonObject["schema_version"]!!
                .jsonObject["const"]!!
                .jsonPrimitive.int
        assertEquals(SingBoxSubscriptionParser.RIPDPI_SCHEMA_VERSION, constVersion)
    }

    // -------------------------------------------------------------------------
    // Paired cohort-fingerprint golden: this side recomputes the exact value
    // the server (ripdpi_cohort_fingerprint.py) commits to the same golden file.
    // -------------------------------------------------------------------------
    @Test
    fun `cohort fingerprint matches the paired golden`() {
        val params = golden["params"]!!.jsonObject
        val computed = paramsFrom(params).cohortFingerprint()
        assertEquals(golden["fingerprint"]!!.jsonPrimitive.content, computed)
    }

    @Test
    fun `example AWG entry fingerprint recomputes from its own params`() {
        val entry = example["amneziawg"]!!.jsonArray[0].jsonObject
        val expected = entry["cohort_fingerprint"]!!.jsonPrimitive.content
        assertEquals(expected, paramsFrom(entry).cohortFingerprint())
    }

    // -------------------------------------------------------------------------
    // The parser accepts the canonical example bundle and surfaces every
    // additive field (cohort_fingerprint, salamander_upstream_tag, topology,
    // expires). The example is a bare ripdpi object; wrap it in a minimal
    // sing-box doc with a matching hysteria2 outbound so the extras attach.
    // -------------------------------------------------------------------------
    @Test
    fun `parser accepts the canonical example bundle and surfaces all fields`() {
        val ripdpi = example
        val hyTag = ripdpi["hysteria_extras"]!!.jsonObject.keys.first()
        val doc =
            buildJsonObject {
                put(
                    "outbounds",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "hysteria2")
                                put("tag", hyTag)
                                put("server", "hy2.example.com")
                                put("server_port", 8443)
                                put("password", "hy2-secret")
                            },
                        )
                    },
                )
                put("ripdpi", ripdpi)
            }

        val result = SingBoxSubscriptionParser.parse(doc.toString(), "contract-test")
        assertTrue("expected Success but got $result", result is SingBoxParseResult.Success)
        result as SingBoxParseResult.Success

        // AmneziaWG entry + cohort fingerprint round-trip and recompute.
        assertEquals(1, result.amneziaWgProfiles.size)
        val awg = result.amneziaWgProfiles[0]
        val exampleFingerprint =
            example["amneziawg"]!!
                .jsonArray[0]
                .jsonObject["cohort_fingerprint"]!!
                .jsonPrimitive.content
        assertEquals(exampleFingerprint, awg.cohortFingerprint)
        assertEquals(awg.cohortFingerprint, awg.awg.cohortFingerprint())

        // Hysteria2 salamander upstream tag attached to the matching profile.
        val hy2 = result.profiles.filterIsInstance<ProxyProfile.Hysteria2>().single()
        assertEquals("v2.9.0", hy2.salamanderUpstreamTag)

        // Topology + expiry surfaced.
        assertNotNull(result.topology)
        assertEquals(false, result.topology!!.splitHopEgress)
        assertEquals(null, result.topology!!.hysteriaRealm)
        assertEquals("2026-12-31T23:59:59Z", result.expiresAt)
    }

    private fun paramsFrom(obj: JsonObject): AmneziaWgParameters =
        AmneziaWgParameters(
            jc = obj["jc"]?.jsonPrimitive?.int,
            jmin = obj["jmin"]?.jsonPrimitive?.int,
            jmax = obj["jmax"]?.jsonPrimitive?.int,
            s1 = obj["s1"]?.jsonPrimitive?.int,
            s2 = obj["s2"]?.jsonPrimitive?.int,
            h1 = obj["h1"]?.jsonPrimitive?.long,
            h2 = obj["h2"]?.jsonPrimitive?.long,
            h3 = obj["h3"]?.jsonPrimitive?.long,
            h4 = obj["h4"]?.jsonPrimitive?.long,
            i1 = obj["i1"]?.jsonPrimitive?.content,
            i2 = obj["i2"]?.jsonPrimitive?.content,
            i3 = obj["i3"]?.jsonPrimitive?.content,
            i4 = obj["i4"]?.jsonPrimitive?.content,
            i5 = obj["i5"]?.jsonPrimitive?.content,
        )

    private companion object {
        const val SCHEMA = "/contract/ripdpi-bundle.schema.json"
        const val EXAMPLE = "/contract/ripdpi-bundle.example.json"
        const val GOLDEN = "/contract/cohort-fingerprint.golden.json"
    }
}
