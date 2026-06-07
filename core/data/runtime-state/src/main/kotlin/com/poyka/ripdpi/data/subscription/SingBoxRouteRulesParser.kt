package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.routing.PackageRoutingAction
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.data.routing.PackageRoutingRuleOrigin
import com.poyka.ripdpi.serialization.RipDpiLenientJson
import kotlinx.serialization.json.Json
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
 * - any other named outbound → [PackageRoutingAction.VIA_OUTBOUND]
 *
 * A bundle that sets the same package in both a bypass rule and a via-tun rule
 * is **malformed**: the deployer flags `--per-app-bypass` and
 * `--per-app-via-tun` are mutually exclusive per package, so the parser rejects
 * the whole bundle with a [SingBoxRouteRulesParseResult.Error] naming the
 * offending `route.rules` index. Malformed JSON also yields an `Error`.
 *
 * Produced rules carry no origin tag from the parser's point of view; the
 * caller stamps them with a [PackageRoutingRuleOrigin.Subscription] id before
 * handing them to [com.poyka.ripdpi.data.routing.PackageRoutingMerge].
 */
object SingBoxRouteRulesParser {
    private val json =
        RipDpiLenientJson

    /**
     * Parses [payload] and extracts its per-app routing rules. The rules in a
     * [SingBoxRouteRulesParseResult.Success] are stamped with a placeholder
     * [PackageRoutingRuleOrigin.User] origin; callers re-tag them with the
     * subscription id. Never throws.
     */
    fun parse(payload: String): SingBoxRouteRulesParseResult {
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
                val ruleEntries = (rootResult.root["route"] as? JsonObject)?.get("rules") as? JsonArray
                ruleEntries?.let { collectPackageRules(it) }
                    ?: SingBoxRouteRulesParseResult.Success(emptyList())
            }
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
    private fun collectPackageRules(ruleEntries: JsonArray): SingBoxRouteRulesParseResult {
        val rules = mutableListOf<PackageRoutingRule>()
        // Track the first action seen per package so a later contradicting
        // entry can be reported with its own route.rules index.
        val seenAction = mutableMapOf<String, PackageRoutingAction>()
        ruleEntries.forEachIndexed { index, element ->
            val obj = element as? JsonObject ?: return@forEachIndexed
            val packageNames = packageNamesOf(obj)
            if (packageNames.isEmpty()) return@forEachIndexed
            val action = mapOutboundToAction((obj["outbound"] as? JsonPrimitive)?.contentOrNull)
            for (packageName in packageNames) {
                val previous = seenAction[packageName]
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
                        origin = PackageRoutingRuleOrigin.User,
                    )
            }
        }
        return SingBoxRouteRulesParseResult.Success(rules)
    }

    /** Non-blank package names from a rule entry's `package_name` array, or empty. */
    private fun packageNamesOf(obj: JsonObject): List<String> =
        (obj["package_name"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { name -> name.isNotBlank() } }
            .orEmpty()

    private fun mapOutboundToAction(outbound: String?): PackageRoutingAction =
        when (outbound?.lowercase()) {
            "direct" -> PackageRoutingAction.BYPASS
            "select", null -> PackageRoutingAction.VIA_TUN
            else -> PackageRoutingAction.VIA_OUTBOUND
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
