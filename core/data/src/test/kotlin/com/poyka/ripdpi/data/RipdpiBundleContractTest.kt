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
import java.io.File

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

    // -------------------------------------------------------------------------
    // Realistic full bundle (sing-box document + ripdpi object), byte-identical
    // to the server's contract/ripdpi-bundle.golden-full.json. The client parses
    // the WHOLE document end-to-end; the server validates the same file's ripdpi
    // object against the schema. This is the cross-repo emit->parse check.
    // -------------------------------------------------------------------------
    @Test
    fun `parser consumes the realistic golden-full bundle end to end`() {
        val result = SingBoxSubscriptionParser.parse(resource(GOLDEN_FULL), "golden-full")
        assertTrue("expected Success but got $result", result is SingBoxParseResult.Success)
        result as SingBoxParseResult.Success

        // Real outbounds imported (vless-reality + hysteria2; selector/urltest skipped).
        assertEquals(2, result.profiles.size)
        val hy2 = result.profiles.filterIsInstance<ProxyProfile.Hysteria2>().single()
        assertEquals("EXAMPLEsalamanderPassword", hy2.obfsPassword)
        assertEquals("v2.9.0", hy2.salamanderUpstreamTag)
        assertEquals(false, hy2.insecure)
        assertEquals("20000:50000", hy2.portHopPorts)
        assertEquals("30s", hy2.portHopInterval)

        // AWG entry with i-values; delivered fingerprint recomputes from its params.
        val awg = result.amneziaWgProfiles.single()
        assertEquals("deadbeef", awg.awg.i1)
        assertEquals("cafebabe", awg.awg.i3)
        assertEquals(
            "sha256:5c6da6e7611473a17003b31df40ba2fba04fac5990dc1da2cb06608ecc45bd25",
            awg.cohortFingerprint,
        )
        assertEquals(awg.cohortFingerprint, awg.awg.cohortFingerprint())

        // Non-default topology + expiry surfaced.
        assertEquals(true, result.topology!!.splitHopEgress)
        assertEquals("realm-a", result.topology!!.hysteriaRealm)
        assertEquals("2026-12-31T23:59:59Z", result.expiresAt)
    }

    // -------------------------------------------------------------------------
    // Negative fixtures (byte-identical to the server's contract/negative/). The
    // server asserts the schema REJECTS each; this side asserts the lenient,
    // forward-compatible parser handles each deterministically WITHOUT throwing.
    // That intentional strict-server / lenient-client asymmetry is the contract.
    // -------------------------------------------------------------------------
    @Test
    fun `parser handles every negative fixture leniently without throwing`() {
        val dir = File(requireNotNull(javaClass.getResource(NEGATIVE_DIR)) { "missing $NEGATIVE_DIR" }.toURI())
        val files =
            requireNotNull(dir.listFiles { f -> f.name.startsWith("neg-") && f.name.endsWith(".json") })
                .sortedBy { it.name }
        assertTrue("no negative fixtures found", files.isNotEmpty())

        for (f in files) {
            val result = SingBoxSubscriptionParser.parse(f.readText(), "neg")
            assertTrue("${f.name}: expected Success (lenient) but got $result", result is SingBoxParseResult.Success)
            result as SingBoxParseResult.Success
            when (f.name) {
                // Unrecognised / absent schema_version => whole ripdpi block ignored.
                "neg-schema-version-2.json", "neg-missing-schema-version.json" -> {
                    assertTrue("${f.name}: ripdpi block should be ignored", result.amneziaWgProfiles.isEmpty())
                }

                // A peer with no public key is unparseable => that AWG entry is skipped.
                "neg-peer-missing-public-key.json" -> {
                    assertTrue("${f.name}: malformed AWG entry should be skipped", result.amneziaWgProfiles.isEmpty())
                }
            }
        }
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
        const val GOLDEN_FULL = "/contract/ripdpi-bundle.golden-full.json"
        const val NEGATIVE_DIR = "/contract/negative"
    }
}
