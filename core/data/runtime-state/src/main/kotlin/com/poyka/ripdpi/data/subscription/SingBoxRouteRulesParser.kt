package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.routing.PackageRoutingAction
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.data.routing.PackageRoutingRuleOrigin
import com.poyka.ripdpi.serialization.RipDpiLenientJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Outcome of a [SingBoxRouteRulesParser] run. */
sealed interface SingBoxRouteRulesParseResult {
    /**
     * Parsing succeeded; [rules] holds the per-app routing rules extracted from
     * `route.rules` (may be empty when the bundle has no `route` section or no
     * `package_name` entries).
     */
    data class Success(
        val rules: List<PackageRoutingRule>,
    ) : SingBoxRouteRulesParseResult

    /**
     * The bundle was rejected. [message] is a human-readable, location-aware
     * reason. When the rejection is a duplicate-package conflict, [ruleIndex]
     * is the `route.rules` index of the offending entry and [packageName] is
     * the conflicting package; both are `null` for a malformed-JSON failure.
     */
    data class Error(
        val message: String,
        val ruleIndex: Int? = null,
        val packageName: String? = null,
    ) : SingBoxRouteRulesParseResult
}

/**
 * Parses the `route.rules` array of a sing-box bundle into per-app
 * [PackageRoutingRule] records.
 *
 * Only `route.rules[]` entries carrying a `package_name` array are kept; every
 * other rule kind (`domain`, `geoip`, `port`, …) is silently ignored — sing-box
 * itself drops `package_name` on non-Android platforms, so the same bundle is
 * portable. The `outbound` field maps to a [PackageRoutingAction]:
 * - `"direct"` → [PackageRoutingAction.BYPASS]
 * - `"select"` → [PackageRoutingAction.VIA_TUN]
 * Any missing or named outbound is rejected because the Android VPN layer can
 * only enforce whole-app bypass or whole-app tunnel membership. Accepting a
 * named outbound would silently widen it to the default tunnel path.
 *
 * A bundle that sets the same package in both a bypass rule and a via-tun rule
 * is **malformed**: the deployer flags `--per-app-bypass` and
 * `--per-app-via-tun` are mutually exclusive per package, so the parser rejects
 * the whole bundle with a [SingBoxRouteRulesParseResult.Error] naming the
 * offending `route.rules` index. Malformed JSON also yields an `Error`.
 *
 * Produced rules are stamped with the importing subscription id.
 */
object SingBoxRouteRulesParser {
    private val json =
        RipDpiLenientJson

    /**
     * Parses [payload] and extracts its per-app routing rules. Never throws.
     */
    fun parse(
        payload: String,
        groupId: String,
    ): SingBoxRouteRulesParseResult {
        val rootResult =
            runCatching { json.parseToJsonElement(payload) as? JsonObject }
                .fold(
                    onSuccess = { root ->
                        root?.let { RootParse.Ok(it) }
                            ?: RootParse.Failed("sing-box JSON root is not an object")
                    },
                    onFailure = { error ->
                        RootParse.Failed("malformed sing-box JSON: ${error.message ?: "could not be parsed"}")
                    },
                )
        return when (rootResult) {
            is RootParse.Failed -> {
                SingBoxRouteRulesParseResult.Error(rootResult.message)
            }

            is RootParse.Ok -> {
                parse(rootResult.root, groupId)
            }
        }
    }

    internal fun parse(
        root: JsonObject,
        groupId: String,
    ): SingBoxRouteRulesParseResult =
        when (val route = root["route"]) {
            null -> {
                SingBoxRouteRulesParseResult.Success(emptyList())
            }

            is JsonObject -> {
                when (val rules = route["rules"]) {
                    null -> SingBoxRouteRulesParseResult.Success(emptyList())
                    is JsonArray -> collectPackageRules(rules, groupId)
                    else -> SingBoxRouteRulesParseResult.Error("sing-box route.rules must be an array")
                }
            }

            else -> {
                SingBoxRouteRulesParseResult.Error("sing-box route must be an object")
            }
        }

    /** Intermediate result of resolving the JSON root before rule extraction. */
    private sealed interface RootParse {
        data class Ok(
            val root: JsonObject,
        ) : RootParse

        data class Failed(
            val message: String,
        ) : RootParse
    }

    /**
     * Walks the `route.rules` array, keeping only `package_name` entries and
     * detecting the malformed bypass-vs-via-tun duplicate-package case.
     */
    private fun collectPackageRules(
        ruleEntries: JsonArray,
        groupId: String,
    ): SingBoxRouteRulesParseResult {
        val rules = mutableListOf<PackageRoutingRule>()
        // Track the first action seen per package so a later contradicting
        // entry can be reported with its own route.rules index.
        val seenAction = mutableMapOf<String, PackageRoutingAction>()
        for ((index, element) in ruleEntries.withIndex()) {
            collectPackageRule(element, index, groupId, rules, seenAction)?.let { return it }
        }
        return SingBoxRouteRulesParseResult.Success(rules)
    }

    private fun collectPackageRule(
        element: JsonElement,
        index: Int,
        groupId: String,
        rules: MutableList<PackageRoutingRule>,
        seenAction: MutableMap<String, PackageRoutingAction>,
    ): SingBoxRouteRulesParseResult.Error? {
        val obj =
            element as? JsonObject
                ?: return SingBoxRouteRulesParseResult.Error(
                    message = "route.rules[$index] must be an object",
                    ruleIndex = index,
                )
        return when (val packageNames = packageNamesOf(obj, index)) {
            PackageNames.Absent -> {
                null
            }

            is PackageNames.Invalid -> {
                SingBoxRouteRulesParseResult.Error(
                    message = packageNames.message,
                    ruleIndex = index,
                )
            }

            is PackageNames.Values -> {
                appendPackageRules(
                    obj = obj,
                    packageNames = packageNames.names,
                    index = index,
                    groupId = groupId,
                    rules = rules,
                    seenAction = seenAction,
                )
            }
        }
    }

    private fun appendPackageRules(
        obj: JsonObject,
        packageNames: List<String>,
        index: Int,
        groupId: String,
        rules: MutableList<PackageRoutingRule>,
        seenAction: MutableMap<String, PackageRoutingAction>,
    ): SingBoxRouteRulesParseResult.Error? {
        val outbound = (obj["outbound"] as? JsonPrimitive)?.contentOrNull
        return when {
            packageNames.isEmpty() -> {
                null
            }

            outbound == "direct" -> {
                appendResolvedPackageRules(
                    packageNames,
                    PackageRoutingAction.BYPASS,
                    index,
                    groupId,
                    rules,
                    seenAction,
                )
            }

            outbound == "select" -> {
                appendResolvedPackageRules(
                    packageNames,
                    PackageRoutingAction.VIA_TUN,
                    index,
                    groupId,
                    rules,
                    seenAction,
                )
            }

            else -> {
                val packageName = packageNames.first()
                SingBoxRouteRulesParseResult.Error(
                    message =
                        "route.rules[$index]: package '$packageName' uses unsupported outbound " +
                            "'${outbound ?: "<missing>"}'",
                    ruleIndex = index,
                    packageName = packageName,
                )
            }
        }
    }

    private fun appendResolvedPackageRules(
        packageNames: List<String>,
        action: PackageRoutingAction,
        index: Int,
        groupId: String,
        rules: MutableList<PackageRoutingRule>,
        seenAction: MutableMap<String, PackageRoutingAction>,
    ): SingBoxRouteRulesParseResult.Error? {
        val conflict =
            packageNames.firstOrNull { packageName ->
                val previous = seenAction[packageName]
                previous != null && isBypassViaTunConflict(previous, action)
            }
        return if (conflict != null) {
            SingBoxRouteRulesParseResult.Error(
                message =
                    "route.rules[$index]: package '$conflict' is set to both bypass and " +
                        "via-tun — these are mutually exclusive",
                ruleIndex = index,
                packageName = conflict,
            )
        } else {
            packageNames.forEach { packageName ->
                if (seenAction[packageName] == action) return@forEach
                seenAction[packageName] = action
                rules +=
                    PackageRoutingRule(
                        packageName = packageName,
                        action = action,
                        origin = PackageRoutingRuleOrigin.Subscription(groupId),
                    )
            }
            null
        }
    }

    private sealed interface PackageNames {
        data object Absent : PackageNames

        data class Values(
            val names: List<String>,
        ) : PackageNames

        data class Invalid(
            val message: String,
        ) : PackageNames
    }

    /** Strict string package names from a rule entry's `package_name` array. */
    private fun packageNamesOf(
        obj: JsonObject,
        index: Int,
    ): PackageNames =
        when (val element = obj["package_name"]) {
            null -> PackageNames.Absent
            is JsonArray -> packageNamesFromArray(element, index)
            else -> PackageNames.Invalid("route.rules[$index].package_name must be an array")
        }

    private fun packageNamesFromArray(
        array: JsonArray,
        index: Int,
    ): PackageNames {
        val invalidIndex =
            array.indexOfFirst { element ->
                val primitive = element as? JsonPrimitive
                primitive?.takeIf { it.isString }?.contentOrNull.isNullOrBlank()
            }
        return if (invalidIndex >= 0) {
            PackageNames.Invalid(
                "route.rules[$index].package_name[$invalidIndex] must be a non-blank string",
            )
        } else {
            PackageNames.Values(array.map { (it as JsonPrimitive).content })
        }
    }

    /** A bypass-vs-via-tun pairing is the malformed case the deployer cannot emit. */
    private fun isBypassViaTunConflict(
        a: PackageRoutingAction,
        b: PackageRoutingAction,
    ): Boolean {
        val pair = setOf(a, b)
        return pair == setOf(PackageRoutingAction.BYPASS, PackageRoutingAction.VIA_TUN)
    }
}
