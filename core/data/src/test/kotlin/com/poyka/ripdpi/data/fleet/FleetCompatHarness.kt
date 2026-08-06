package com.poyka.ripdpi.data.fleet

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.subscription.SelectorUrltestGroupImport
import com.poyka.ripdpi.data.subscription.SelectorUrltestImportResult
import com.poyka.ripdpi.data.subscription.SingBoxParseResult
import com.poyka.ripdpi.data.subscription.SingBoxSubscriptionParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A single fleet-compat fixture: the literal `emit-singbox.sh`-style bundle
 * plus its golden snapshots, loaded from
 * `src/test/resources/fleet-fixtures/<scenario>/`.
 */
data class FleetFixture(
    val scenario: String,
    val bundleJson: String,
    val expectedProfilesJson: String,
    val expectedGroupJson: String?,
    val expectedRoutingJson: String?,
    val metaJson: String,
) {
    companion object {
        private fun resource(path: String): String? =
            FleetFixture::class.java.classLoader
                ?.getResourceAsStream(path)
                ?.bufferedReader()
                ?.use { it.readText() }

        /** Loads every artifact of [scenario]; throws when a required file is missing. */
        fun load(scenario: String): FleetFixture {
            val root = "fleet-fixtures/$scenario"
            val bundle =
                resource("$root/bundle.json")
                    ?: error("missing $root/bundle.json")
            val expectedProfiles =
                resource("$root/expected-profiles.json")
                    ?: error("missing $root/expected-profiles.json")
            val meta =
                resource("$root/meta.json")
                    ?: error("missing $root/meta.json")
            return FleetFixture(
                scenario = scenario,
                bundleJson = bundle,
                expectedProfilesJson = expectedProfiles,
                expectedGroupJson = resource("$root/expected-group.json"),
                expectedRoutingJson = resource("$root/expected-routing.json"),
                metaJson = meta,
            )
        }
    }
}

/**
 * Outcome of importing one fixture through the production phase-1 pipeline and
 * diffing it against the golden snapshots.
 */
data class FleetCompatResult(
    val scenario: String,
    val matches: Boolean,
    val diff: String,
    val importedProfiles: List<ProxyProfile>,
    val importedGroup: ProxyGroup?,
    val importedRoutingTags: List<String>,
    val roundTripWithinAllowedDeltas: Boolean,
    val roundTripDiff: String,
    val bootstrapConsumedOnce: Boolean,
)

/**
 * Golden-file harness for the ripdpi-vpn-deploy fleet-compat suite.
 *
 * For each scenario it imports `bundle.json` through the **production** phase-1
 * parsers ([SingBoxSubscriptionParser], [SelectorUrltestGroupImport]),
 * normalises the result to a stable structural shape, and diffs that against
 * the scenario's `expected-*.json`. A mismatch is reported as a readable,
 * jq-style structural diff rather than an opaque assertion failure.
 *
 * The harness is deliberately self-contained: no network, no Android runtime,
 * pure JVM. The `bootstrap-bundle` scenario fakes its one-shot HTTP backend
 * with [FakeBootstrapBackend].
 */
object FleetCompatHarness {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = true
        }

    /** Production-token shapes a fixture must never contain (it must use frozen test values). */
    private val productionTokenShapes =
        listOf(
            // A real-looking UUID that is NOT the frozen all-zero / -fixture pattern.
            Regex(
                "\"uuid\"\\s*:\\s*\"(?!0{8}-0{4}-0{4}-0{4}-0{11}[0-9a-f])" +
                    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\"",
            ),
        )

    /** The group id every harness import is stamped with. */
    private const val HARNESS_GROUP_ID = "fleet-compat"

    /** Runs [scenario] through the production import pipeline and diffs the golden snapshots. */
    fun run(scenario: String): FleetCompatResult {
        val fixture = FleetFixture.load(scenario)
        return evaluate(fixture, fixture.bundleJson)
    }

    /**
     * Runs [scenario] but applies [mutate] to the bundle bytes first. Used to
     * prove the suite goes red on a deployer-schema / parser regression.
     */
    fun runWithMutation(
        scenario: String,
        mutate: (String) -> String,
    ): FleetCompatResult {
        val fixture = FleetFixture.load(scenario)
        return evaluate(fixture, mutate(fixture.bundleJson))
    }

    /** Returns the production-token-shaped substrings found in [bundleJson] (empty when clean). */
    fun findProductionTokenShapes(bundleJson: String): List<String> =
        productionTokenShapes.flatMap { shape ->
            shape.findAll(bundleJson).map { it.value }.toList()
        }

    private fun evaluate(
        fixture: FleetFixture,
        bundleJson: String,
    ): FleetCompatResult {
        val diffs = mutableListOf<String>()

        // --- profiles: production sing-box parser ---
        val parsed = SingBoxSubscriptionParser.parse(bundleJson, HARNESS_GROUP_ID)
        val rawProfiles =
            when (parsed) {
                is SingBoxParseResult.Error -> {
                    diffs += "profiles: parser error: ${parsed.message}"
                    emptyList()
                }

                is SingBoxParseResult.Success -> {
                    parsed.profiles
                }
            }
        // The production parser excludes selector/urltest/direct/block/dns
        // metadata before returning concrete remote profiles.
        val importedProfiles = rawProfiles
        val actualProfileShape = importedProfiles.map { it.toStableShape() }
        val expectedProfileShape = parseExpectedProfiles(fixture.expectedProfilesJson)
        if (actualProfileShape != expectedProfileShape) {
            diffs += structuralDiff("profiles", expectedProfileShape, actualProfileShape)
        }

        // --- group: production selector/urltest import ---
        val importResult = SelectorUrltestGroupImport.import(bundleJson, HARNESS_GROUP_ID)
        val importedGroup =
            when (importResult) {
                is SelectorUrltestImportResult.Error -> {
                    if (fixture.expectedGroupJson != null) {
                        diffs += "group: import error: ${importResult.message}"
                    }
                    null
                }

                is SelectorUrltestImportResult.Success -> {
                    importResult.group
                }
            }
        if (fixture.expectedGroupJson != null) {
            val expectedGroup = parseExpectedGroup(fixture.expectedGroupJson)
            val actualGroup =
                (importResult as? SelectorUrltestImportResult.Success)?.let { ok ->
                    ok.group?.let { g ->
                        mapOf(
                            "name" to g.name,
                            "isSelector" to g.isSelector.toString(),
                            "memberOrder" to ok.memberOrder.joinToString(","),
                            "default" to (ok.defaultMemberTag ?: ""),
                        )
                    }
                }
            if (actualGroup != expectedGroup) {
                diffs += structuralDiff("group", expectedGroup, actualGroup)
            }
        }

        // --- routing: package_name rules read straight from the bundle ---
        val importedRoutingTags = extractRoutingOutbounds(bundleJson)
        if (fixture.expectedRoutingJson != null) {
            val expectedRouting = parseExpectedRouting(fixture.expectedRoutingJson)
            if (importedRoutingTags != expectedRouting) {
                diffs += structuralDiff("routing", expectedRouting, importedRoutingTags)
            }
        }

        // --- round-trip: re-export and diff modulo documented allowed deltas ---
        val (roundTripOk, roundTripDiff) = roundTrip(bundleJson, importedProfiles)

        // --- bootstrap one-shot consumption ---
        val bootstrapConsumedOnce =
            if (fixture.scenario == "bootstrap-bundle") {
                val backend = FakeBootstrapBackend(token = "bootstrap-token-fixture", payload = bundleJson)
                val first = backend.consume("bootstrap-token-fixture") != null
                val second = backend.consume("bootstrap-token-fixture") == null
                first && second
            } else {
                true
            }

        return FleetCompatResult(
            scenario = fixture.scenario,
            matches = diffs.isEmpty(),
            diff = if (diffs.isEmpty()) "no diff" else diffs.joinToString("\n"),
            importedProfiles = importedProfiles,
            importedGroup = importedGroup,
            importedRoutingTags = importedRoutingTags,
            roundTripWithinAllowedDeltas = roundTripOk,
            roundTripDiff = roundTripDiff,
            bootstrapConsumedOnce = bootstrapConsumedOnce,
        )
    }

    /**
     * Re-exports [profiles] back to a minimal sing-box outbound list and checks
     * the set of (type, server, server_port) tuples round-trips. Documented
     * allowed deltas: outbound ordering, the selector/urltest/direct/block/dns
     * boilerplate outbounds, and the `default:"auto"` idempotency — none of
     * those participate in the comparison.
     */
    private fun roundTrip(
        bundleJson: String,
        profiles: List<ProxyProfile>,
    ): Pair<Boolean, String> {
        val bundleNodes = concreteOutboundTuples(bundleJson)
        val reExportedNodes =
            profiles
                .mapNotNull { it.toNodeTupleOrNull() }
                .toSet()
        return if (bundleNodes == reExportedNodes) {
            true to "no round-trip diff"
        } else {
            false to
                structuralDiff(
                    "round-trip",
                    bundleNodes.sorted(),
                    reExportedNodes.sorted(),
                )
        }
    }

    /** The concrete (non-group, non-boilerplate) outbound tuples in [bundleJson]. */
    private fun concreteOutboundTuples(bundleJson: String): Set<String> {
        val outbounds = outboundsArray(bundleJson) ?: return emptySet()
        return outbounds
            .filterIsInstance<JsonObject>()
            .mapNotNull { obj ->
                val type = obj.str("type") ?: return@mapNotNull null
                if (type in BOILERPLATE_TYPES) return@mapNotNull null
                // Hysteria2 server_ports changes the dial endpoint set and is not
                // representable by the native relay DTO. The importer rejects it
                // instead of silently dialing server_port, so it is intentionally
                // absent from the re-exported runnable-node set.
                if (type == "hysteria2" && obj["server_ports"] != null) return@mapNotNull null
                val server = obj.str("server") ?: return@mapNotNull null
                val port = obj.intStr("server_port") ?: return@mapNotNull null
                "$type|$server|$port"
            }.toSet()
    }

    private val BOILERPLATE_TYPES = setOf("selector", "urltest", "direct", "block", "dns")

    private fun ProxyProfile.toNodeTupleOrNull(): String? =
        when (this) {
            is ProxyProfile.Vless -> "vless|$server|$serverPort"

            // Round-trips against the sing-box bundle, whose wire type for a REALITY
            // outbound is `vless` (reality lives in tls.reality, not a distinct type).
            // The internal VlessReality split is asserted via toStableShape instead.
            is ProxyProfile.VlessReality -> "vless|$server|$serverPort"

            is ProxyProfile.Shadowsocks -> "shadowsocks|$server|$serverPort"

            is ProxyProfile.Trojan -> "trojan|$server|$serverPort"

            is ProxyProfile.Hysteria2 -> "hysteria2|$server|$serverPort"

            is ProxyProfile.AnyTls -> "anytls|$server|$serverPort"

            is ProxyProfile.Mieru -> "mieru|$server|$serverPort"

            is ProxyProfile.Ssh -> "ssh|$server|$serverPort"

            is ProxyProfile.RawConfig -> null
        }

    /** A stable, comparison-friendly shape for one imported profile. */
    private fun ProxyProfile.toStableShape(): Map<String, String> =
        when (this) {
            is ProxyProfile.Vless -> {
                mapOf("type" to "vless", "name" to displayName, "server" to server, "port" to serverPort.toString())
            }

            is ProxyProfile.VlessReality -> {
                mapOf(
                    "type" to "vless-reality",
                    "name" to displayName,
                    "server" to server,
                    "port" to serverPort.toString(),
                )
            }

            is ProxyProfile.Shadowsocks -> {
                mapOf(
                    "type" to "shadowsocks",
                    "name" to displayName,
                    "server" to server,
                    "port" to serverPort.toString(),
                )
            }

            is ProxyProfile.Trojan -> {
                mapOf("type" to "trojan", "name" to displayName, "server" to server, "port" to serverPort.toString())
            }

            is ProxyProfile.Hysteria2 -> {
                mapOf("type" to "hysteria2", "name" to displayName, "server" to server, "port" to serverPort.toString())
            }

            is ProxyProfile.AnyTls -> {
                mapOf("type" to "anytls", "name" to displayName, "server" to server, "port" to serverPort.toString())
            }

            is ProxyProfile.Mieru -> {
                mapOf("type" to "mieru", "name" to displayName, "server" to server, "port" to serverPort.toString())
            }

            is ProxyProfile.Ssh -> {
                mapOf("type" to "ssh", "name" to displayName, "server" to server, "port" to serverPort.toString())
            }

            is ProxyProfile.RawConfig -> {
                mapOf("type" to "raw-config", "name" to displayName, "server" to "", "port" to "")
            }
        }

    private fun parseExpectedProfiles(jsonText: String): List<Map<String, String>> {
        val arr = json.parseToJsonElement(jsonText) as? JsonArray ?: return emptyList()
        return arr.filterIsInstance<JsonObject>().map { obj ->
            mapOf(
                "type" to (obj.str("type") ?: ""),
                "name" to (obj.str("name") ?: ""),
                "server" to (obj.str("server") ?: ""),
                "port" to (obj.intStr("port") ?: ""),
            )
        }
    }

    private fun parseExpectedGroup(jsonText: String): Map<String, String> {
        val obj = json.parseToJsonElement(jsonText) as? JsonObject ?: return emptyMap()
        return mapOf(
            "name" to (obj.str("name") ?: ""),
            "isSelector" to (obj.str("isSelector") ?: "true"),
            "memberOrder" to (obj.str("memberOrder") ?: ""),
            "default" to (obj.str("default") ?: ""),
        )
    }

    private fun parseExpectedRouting(jsonText: String): List<String> {
        val arr = json.parseToJsonElement(jsonText) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }

    /** Outbound tags named by `route.rules[].outbound` — the per-app routing surface. */
    @Suppress("ReturnCount")
    private fun extractRoutingOutbounds(bundleJson: String): List<String> {
        val root = json.parseToJsonElement(bundleJson) as? JsonObject ?: return emptyList()
        val route = root["route"] as? JsonObject ?: return emptyList()
        val rules = route["rules"] as? JsonArray ?: return emptyList()
        return rules
            .filterIsInstance<JsonObject>()
            .filter { it["package_name"] != null }
            .mapNotNull { it.str("outbound") }
    }

    private fun outboundsArray(bundleJson: String): JsonArray? {
        val root = json.parseToJsonElement(bundleJson)
        return when (root) {
            is JsonObject -> root["outbounds"] as? JsonArray
            is JsonArray -> root
            else -> null
        }
    }

    /** Builds a readable, jq-style "expected vs actual" structural diff. */
    private fun structuralDiff(
        label: String,
        expected: Any?,
        actual: Any?,
    ): String =
        buildString {
            append("[$label] structural mismatch\n")
            append("  expected: ").append(expected).append('\n')
            append("  actual:   ").append(actual)
        }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.intStr(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.contentOrNull?.trim()?.takeIf { it.toIntOrNull() != null }
    }
}
