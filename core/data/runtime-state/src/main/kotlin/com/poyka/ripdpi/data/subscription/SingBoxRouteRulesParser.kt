package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.routing.PackageRoutingAction
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.data.routing.PackageRoutingRuleOrigin
import com.poyka.ripdpi.serialization.RipDpiLenientJson
import kotlinx.serialization.json.JsonArray
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
    ): SingBoxRouteRulesParseResult {
        val routeElement = root["route"] ?: return SingBoxRouteRulesParseResult.Success(emptyList())
        val route =
            routeElement as? JsonObject
                ?: return SingBoxRouteRulesParseResult.Error("sing-box route must be an object")
        val rulesElement = route["rules"] ?: return SingBoxRouteRulesParseResult.Success(emptyList())
        val ruleEntries =
            rulesElement as? JsonArray
                ?: return SingBoxRouteRulesParseResult.Error("sing-box route.rules must be an array")
        return collectPackageRules(ruleEntries, groupId)
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
        ruleEntries.forEachIndexed { index, element ->
            val obj =
                element as? JsonObject
                    ?: return SingBoxRouteRulesParseResult.Error(
                        message = "route.rules[$index] must be an object",
                        ruleIndex = index,
                    )
            val packageNames =
                when (val parsed = packageNamesOf(obj, index)) {
                    PackageNames.Absent -> {
                        return@forEachIndexed
                    }

                    is PackageNames.Invalid -> {
                        return SingBoxRouteRulesParseResult.Error(
                            message = parsed.message,
                            ruleIndex = index,
                        )
                    }

                    is PackageNames.Values -> {
                        parsed.names
                    }
                }
            if (packageNames.isEmpty()) return@forEachIndexed
            val outbound = (obj["outbound"] as? JsonPrimitive)?.contentOrNull
            val action =
                when (outbound) {
                    "direct" -> {
                        PackageRoutingAction.BYPASS
                    }

                    "select" -> {
                        PackageRoutingAction.VIA_TUN
                    }

                    else -> {
                        val packageName = packageNames.first()
                        return SingBoxRouteRulesParseResult.Error(
                            message =
                                "route.rules[$index]: package '$packageName' uses unsupported outbound " +
                                    "'${outbound ?: "<missing>"}'",
                            ruleIndex = index,
                            packageName = packageName,
                        )
                    }
                }
            for (packageName in packageNames) {
                val previous = seenAction[packageName]
                if (previous == action) continue
                if (previous != null && previous != action && isBypassViaTunConflict(previous, action)) {
                    return SingBoxRouteRulesParseResult.Error(
                        message =
                            "route.rules[$index]: package '$packageName' is set to both bypass and " +
                                "via-tun — these are mutually exclusive",
                        ruleIndex = index,
                        packageName = packageName,
                    )
                }
                seenAction[packageName] = action
                rules +=
                    PackageRoutingRule(
                        packageName = packageName,
                        action = action,
                        origin = PackageRoutingRuleOrigin.Subscription(groupId),
                    )
            }
        }
        return SingBoxRouteRulesParseResult.Success(rules)
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
    ): PackageNames {
        val element = obj["package_name"] ?: return PackageNames.Absent
        val array =
            element as? JsonArray
                ?: return PackageNames.Invalid("route.rules[$index].package_name must be an array")
        val names = mutableListOf<String>()
        array.forEachIndexed { packageIndex, packageElement ->
            val primitive = packageElement as? JsonPrimitive
            val name = primitive?.takeIf { it.isString }?.contentOrNull
            if (name.isNullOrBlank()) {
                return PackageNames.Invalid(
                    "route.rules[$index].package_name[$packageIndex] must be a non-blank string",
                )
            }
            names += name
        }
        return PackageNames.Values(names)
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
