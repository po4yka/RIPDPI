package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.subscription.EndpointHealth
import com.poyka.ripdpi.data.subscription.EndpointHealthOrder
import com.poyka.ripdpi.data.subscription.EndpointHealthStatus
import com.poyka.ripdpi.data.subscription.SelectorUrltestGroupImport
import com.poyka.ripdpi.data.subscription.SelectorUrltestImportResult
import com.poyka.ripdpi.data.subscription.SingBoxBundleErrorCode
import com.poyka.ripdpi.data.subscription.SingBoxParseResult
import com.poyka.ripdpi.data.subscription.SingBoxSubscriptionParser
import com.poyka.ripdpi.data.subscription.membersByHealth
import com.poyka.ripdpi.data.wireguard.AmneziaWgParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

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
    private val awgArm64Floor: JsonObject get() = json.parseToJsonElement(resource(AWG_ARM64_FLOOR)).jsonObject

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

    @Test
    fun `arm64 S3-S4 contract retains the guard until physical revalidation`() {
        assertEquals(true, awgArm64Floor["guard_required"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(awgArm64Floor["candidate_safe_floor"] is JsonNull)
        assertTrue(awgArm64Floor["verified_safe_floor"] is JsonNull)
        assertEquals(
            "v0.2.18",
            awgArm64Floor["ripdpi_reference"]!!
                .jsonObject["upstream_semantics_version"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `recorded arm64 emulator observations remain distinct from the shared floor`() {
        val verified = json.parseToJsonElement(resource("/awg-arm64-emulator-observation.json")).jsonObject
        val results = verified["results"]!!.jsonObject
        assertEquals("android_arm64_emulator", verified["evidence_kind"]!!.jsonPrimitive.content)
        assertEquals("android-arm64-emulator", verified["platform"]!!.jsonPrimitive.content)
        assertEquals(false, verified["physical_device_revalidated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("48/48", results["amneziavpn_5x_sessions"]!!.jsonPrimitive.content)
        assertEquals("48/48", results["s3_s4_zero_control_sessions"]!!.jsonPrimitive.content)
        assertEquals(0, results["lost_udp_datagrams"]!!.jsonPrimitive.int)
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
        assertEquals(1_798_761_599_000L, result.tokenExpiresAtEpochMillis)

        // Per-endpoint health keyed by server_ref, and the field-report block.
        assertEquals(
            mapOf(
                "p2-awg-phone" to
                    EndpointHealth(
                        EndpointHealthStatus.HEALTHY,
                        epochMillis("2026-11-19T10:00:00Z"),
                        900,
                    ),
                "p2-hysteria2-upcloud-prod" to
                    EndpointHealth(EndpointHealthStatus.DEGRADED, epochMillis("2026-11-19T10:00:00Z"), 900),
            ),
            result.endpointHealth,
        )
        val fieldReport = requireNotNull(result.fieldReport)
        assertEquals("https://reports.example.test/v1/field-report", fieldReport.endpoint)
        assertEquals("EXAMPLE-cohort-token-not-real", fieldReport.cohortToken)
        assertEquals(4, fieldReport.maxReportsPerDay)
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
        assertEquals("20000-50000", hy2.portHopping?.ports)
        assertEquals(30000L, hy2.portHopping?.intervalMillis)

        // AWG entry with i-values; delivered fingerprint recomputes from its params.
        val awg = result.amneziaWgProfiles.single()
        assertEquals("<b 0xdeadbeef><r 4><rd 2><rc 2><t>", awg.awg.i1)
        assertEquals("1000-1999", awg.awg.h1)
        assertEquals("<b 0xcafebabe>", awg.awg.i3)
        assertEquals(
            "sha256:51912e328885b16e0c4053a7ed93f786996b98c75a4d4a151de370d08b5db57d",
            awg.cohortFingerprint,
        )
        assertEquals(awg.cohortFingerprint, awg.awg.cohortFingerprint())

        // Non-default topology + expiry surfaced.
        assertEquals(true, result.topology!!.splitHopEgress)
        assertEquals("realm-a", result.topology!!.hysteriaRealm)
        assertEquals(1_798_761_599_000L, result.tokenExpiresAtEpochMillis)

        // Health for every proxy and AWG tag, plus the field-report block.
        val observedAt = epochMillis("2026-11-19T10:00:00Z")
        assertEquals(
            mapOf(
                "p0-reality-upcloud-prod" to EndpointHealth(EndpointHealthStatus.HEALTHY, observedAt, 900),
                "p2-hysteria2-upcloud-prod" to EndpointHealth(EndpointHealthStatus.DOWN, observedAt, 900),
                "p2-awg-phone" to EndpointHealth(EndpointHealthStatus.UNKNOWN, observedAt, 900),
            ),
            result.endpointHealth,
        )
        assertEquals(4, result.fieldReport?.maxReportsPerDay)
    }

    // -------------------------------------------------------------------------
    // Health-driven failover ordering on the vendored bundles. Unexpired down is
    // excluded; once observed_at + ttl_seconds has passed, the same endpoint is
    // neutral again and keeps its declared position.
    // -------------------------------------------------------------------------
    @Test
    fun `unexpired down endpoint is excluded from the golden-full selector`() {
        assertEquals(
            listOf("p0-reality-upcloud-prod"),
            selectorMembers(GOLDEN_FULL, epochMillis("2026-11-19T10:05:00Z")),
        )
    }

    @Test
    fun `expired down endpoint returns to its neutral position`() {
        assertEquals(
            listOf("p0-reality-upcloud-prod", "p2-hysteria2-upcloud-prod"),
            selectorMembers(GOLDEN_FULL, epochMillis("2026-11-19T10:15:00Z")),
        )
    }

    @Test
    fun `import stores every selector member with its health`() {
        val group = importedGroup(resource(GOLDEN_FULL))
        assertEquals(
            listOf("p0-reality-upcloud-prod", "p2-hysteria2-upcloud-prod"),
            group.members.map { it.displayName },
        )
        assertEquals(setOf("p0-reality-upcloud-prod", "p2-hysteria2-upcloud-prod"), group.memberHealth.keys)
    }

    @Test
    fun `all-down selector imports and fails open with a typed outcome`() {
        val document = json.parseToJsonElement(resource(GOLDEN_FULL)).jsonObject
        val ripdpi = document["ripdpi"]!!.jsonObject
        val endpoints = ripdpi["endpoints"]!!.jsonObject
        val allDown =
            JsonObject(
                document +
                    (
                        "ripdpi" to
                            JsonObject(
                                ripdpi +
                                    (
                                        "endpoints" to
                                            JsonObject(
                                                endpoints.mapValues { (_, entry) ->
                                                    val health = entry.jsonObject["health"]!!.jsonObject
                                                    JsonObject(
                                                        mapOf(
                                                            "health" to
                                                                JsonObject(
                                                                    health + ("status" to JsonPrimitive("down")),
                                                                ),
                                                        ),
                                                    )
                                                },
                                            )
                                    ),
                            )
                    ),
            ).toString()
        val imported =
            SelectorUrltestGroupImport.import(allDown, "health", epochMillis("2026-11-19T10:05:00Z"))
                as SelectorUrltestImportResult.Success
        val group = requireNotNull(imported.group)
        assertTrue(imported.endpointHealth is EndpointHealthOrder.AllDownFailOpen)
        assertEquals(group.members, imported.endpointHealth?.members)
        assertEquals(
            listOf("p0-reality-upcloud-prod", "p2-hysteria2-upcloud-prod"),
            group.membersByHealth(epochMillis("2026-11-19T10:05:00Z")).members.map { it.displayName },
        )
    }

    @Test
    fun `health-expired bundle is neutral at its evaluation time`() {
        val document = json.parseToJsonElement(resource(HEALTH_EXPIRED)).jsonObject
        val evaluatedAt = epochMillis(document["_evaluated_at"]!!.jsonPrimitive.content)
        val health =
            (SingBoxSubscriptionParser.parse(resource(HEALTH_EXPIRED), "expired") as SingBoxParseResult.Success)
                .endpointHealth
        assertTrue(health.isNotEmpty())
        health.values.forEach { assertEquals(EndpointHealthStatus.UNKNOWN, it.statusAt(evaluatedAt)) }
        assertEquals(
            listOf("p0-reality-upcloud-prod", "p2-hysteria2-upcloud-prod"),
            selectorMembers(HEALTH_EXPIRED, evaluatedAt),
        )
    }

    @Test
    fun `hopping import vector preserves its exact destination set and cadence`() {
        val result =
            SingBoxSubscriptionParser.parse(
                resource("/contract/ripdpi-bundle.hysteria-port-hopping.json"),
                "hopping-import",
            )
        assertTrue("expected Success but got $result", result is SingBoxParseResult.Success)
        val profile =
            (result as SingBoxParseResult.Success)
                .profiles
                .filterIsInstance<ProxyProfile.Hysteria2>()
                .single()
        assertEquals(443, profile.serverPort)
        assertEquals(Hysteria2PortHopping("20000-21000", 45_000L), profile.portHopping)
        assertTrue(!profile.obfsPassword.isNullOrBlank())
        assertEquals(false, profile.insecure)
        assertTrue(!profile.serverName.isNullOrBlank())
    }

    // -------------------------------------------------------------------------
    // Negative fixtures (byte-identical to the server's contract/negative/). The
    // server asserts the schema REJECTS each; this side asserts the lenient,
    // forward-compatible parser handles each deterministically WITHOUT throwing.
    // That intentional strict-server / lenient-client asymmetry is the contract.
    // -------------------------------------------------------------------------
    @Test
    fun `parser rejects incompatible contracts and skips malformed entries without throwing`() {
        val dir = File(requireNotNull(javaClass.getResource(NEGATIVE_DIR)) { "missing $NEGATIVE_DIR" }.toURI())
        val files =
            requireNotNull(dir.listFiles { f -> f.name.startsWith("neg-") && f.name.endsWith(".json") })
                .sortedBy { it.name }
        assertTrue("no negative fixtures found", files.isNotEmpty())

        for (f in files) {
            val result = SingBoxSubscriptionParser.parse(f.readText(), "neg")
            when (f.name) {
                "neg-schema-version-2.json", "neg-missing-schema-version.json" -> {
                    assertTrue("${f.name}: expected recognized refusal", result is SingBoxParseResult.Error)
                    result as SingBoxParseResult.Error
                    assertTrue(result.recognizedBundle)
                    assertEquals(SingBoxBundleErrorCode.UNSUPPORTED_SCHEMA_VERSION, result.code)
                }

                "neg-field-report-http-endpoint.json" -> {
                    assertTrue("${f.name}: expected refusal", result is SingBoxParseResult.Error)
                    result as SingBoxParseResult.Error
                    assertEquals(SingBoxBundleErrorCode.INVALID_FIELD_REPORT, result.code)
                    assertTrue(result.message.contains("field_report.endpoint"))
                }

                else -> {
                    assertTrue("${f.name}: expected Success but got $result", result is SingBoxParseResult.Success)
                    result as SingBoxParseResult.Success
                    if (f.name in
                        setOf(
                            "neg-peer-missing-public-key.json",
                            "neg-awg-missing-h1.json",
                            "neg-awg-unknown-field.json",
                        )
                    ) {
                        assertTrue(result.amneziaWgProfiles.isEmpty())
                    }
                    if (f.name in setOf("neg-health-unknown-status.json", "neg-endpoint-ref-hostname.json")) {
                        assertTrue("${f.name}: invalid health must stay neutral", result.endpointHealth.isEmpty())
                    }
                }
            }
        }
    }

    private fun importedGroup(payload: String): ProxyGroup {
        val imported = SelectorUrltestGroupImport.import(payload, "health", epochMillis("2026-11-19T10:05:00Z"))
        assertTrue("expected Success but got $imported", imported is SelectorUrltestImportResult.Success)
        return requireNotNull((imported as SelectorUrltestImportResult.Success).group)
    }

    /** Failover candidates of the stored group, with health evaluated at selection time [nowEpochMillis]. */
    private fun selectorMembers(
        path: String,
        nowEpochMillis: Long,
    ): List<String> = importedGroup(resource(path)).membersByHealth(nowEpochMillis).members.map { it.displayName }

    private fun epochMillis(instant: String): Long = Instant.parse(instant).toEpochMilli()

    private fun paramsFrom(obj: JsonObject): AmneziaWgParameters =
        AmneziaWgParameters(
            jc = obj["jc"]?.jsonPrimitive?.int,
            jmin = obj["jmin"]?.jsonPrimitive?.int,
            jmax = obj["jmax"]?.jsonPrimitive?.int,
            s1 = obj["s1"]?.jsonPrimitive?.int,
            s2 = obj["s2"]?.jsonPrimitive?.int,
            h1 = obj["h1"]?.jsonPrimitive?.content,
            h2 = obj["h2"]?.jsonPrimitive?.content,
            h3 = obj["h3"]?.jsonPrimitive?.content,
            h4 = obj["h4"]?.jsonPrimitive?.content,
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
        const val HEALTH_EXPIRED = "/contract/ripdpi-bundle.health-expired.json"
        const val AWG_ARM64_FLOOR = "/contract/amneziawg-arm64-version-floor.json"
        const val NEGATIVE_DIR = "/contract/negative"
    }
}
