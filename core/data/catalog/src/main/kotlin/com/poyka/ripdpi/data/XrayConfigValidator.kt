package com.poyka.ripdpi.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Catalog-publish-time validator for Xray-style outbound configurations.
 *
 * Catches three classes of error that xray-core has documented but does
 * not refuse at parse time:
 *
 * - VLESS outbound missing a `flow` value (deprecated 2026-06-01).
 * - `streamSettings.tlsSettings.allowInsecure = true` (auto-disabled
 *   2026-06-01).
 * - `streamSettings.network = "xhttp"` combined with
 *   `streamSettings.security = "reality"` at xray-core v26.1.18 or
 *   later (known wire break).
 *
 * The validator operates on a `JsonObject` representation of an Xray
 * config so it can be wired into any future import flow without
 * pre-committing to a typed schema. Forward-looking: no in-tree
 * consumer today (the typed `VlessOutbound` schema does not expose
 * `flow` or `allowInsecure`, and the native wire layer hardcodes the
 * Vision flow). Tracks
 * Completed task `render-validated-xray-client-configs` (see git history).
 */
object XrayConfigValidator {
    /**
     * Context for time- and version-dependent rules.
     *
     * @property upstreamTag the xray-core release tag the host-pack is
     *   targeting. Validation rules can branch on this; for example,
     *   the REALITY+XHTTP wire break is only enforced at `v26.1.18+`.
     */
    data class Context(
        val upstreamTag: String,
    )

    enum class ErrorCode {
        VLESS_FLOW_MISSING,
        ALLOW_INSECURE_DISABLED,
        REALITY_XHTTP_BROKEN_AT_TAG,
    }

    data class ValidationError(
        val code: ErrorCode,
        val path: String,
        val message: String,
    )

    /**
     * Validate a parsed Xray config and return every error found. An
     * empty list means the config is acceptable in the given context.
     */
    fun validate(
        config: JsonObject,
        context: Context,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val outbounds = config["outbounds"] as? JsonArray ?: return errors

        outbounds.forEachIndexed { index, element ->
            val outbound = element as? JsonObject ?: return@forEachIndexed
            val protocol = (outbound["protocol"] as? JsonPrimitive)?.contentOrNull
            val streamSettings = outbound["streamSettings"] as? JsonObject
            val pathPrefix = "outbounds[$index]"

            if (protocol == "vless") {
                errors += checkVlessFlow(outbound, pathPrefix)
            }

            if (streamSettings != null) {
                errors += checkAllowInsecure(streamSettings, "$pathPrefix.streamSettings")
                errors += checkRealityXhttpCombo(streamSettings, "$pathPrefix.streamSettings", context)
            }
        }

        return errors
    }

    private fun checkVlessFlow(
        outbound: JsonObject,
        pathPrefix: String,
    ): List<ValidationError> {
        val vnext = outbound["settings"]?.jsonObject?.get("vnext") as? JsonArray ?: return emptyList()
        val errors = mutableListOf<ValidationError>()
        vnext.forEachIndexed { vIndex, vElement ->
            val users = (vElement as? JsonObject)?.get("users") as? JsonArray ?: return@forEachIndexed
            users.forEachIndexed { uIndex, uElement ->
                val user = uElement as? JsonObject ?: return@forEachIndexed
                val flow = (user["flow"] as? JsonPrimitive)?.contentOrNull
                if (flow.isNullOrEmpty()) {
                    errors +=
                        ValidationError(
                            code = ErrorCode.VLESS_FLOW_MISSING,
                            path = "$pathPrefix.settings.vnext[$vIndex].users[$uIndex].flow",
                            message =
                                "VLESS user is missing required 'flow' (xray-core deprecated " +
                                    "VLESS-without-flow on 2026-06-01).",
                        )
                }
            }
        }
        return errors
    }

    private fun checkAllowInsecure(
        streamSettings: JsonObject,
        pathPrefix: String,
    ): List<ValidationError> {
        val tls = streamSettings["tlsSettings"] as? JsonObject
        val allowInsecure = (tls?.get("allowInsecure") as? JsonPrimitive)?.booleanOrNull
        return if (allowInsecure == true) {
            listOf(
                ValidationError(
                    code = ErrorCode.ALLOW_INSECURE_DISABLED,
                    path = "$pathPrefix.tlsSettings.allowInsecure",
                    message =
                        "tlsSettings.allowInsecure=true is auto-disabled in xray-core " +
                            "from 2026-06-01; remove or set to false.",
                ),
            )
        } else {
            emptyList()
        }
    }

    private fun checkRealityXhttpCombo(
        streamSettings: JsonObject,
        pathPrefix: String,
        context: Context,
    ): List<ValidationError> {
        val network = (streamSettings["network"] as? JsonPrimitive)?.contentOrNull
        val security = (streamSettings["security"] as? JsonPrimitive)?.contentOrNull
        if (network != "xhttp" || security != "reality") return emptyList()
        return if (isBrokenRealityXhttpTag(context.upstreamTag)) {
            listOf(
                ValidationError(
                    code = ErrorCode.REALITY_XHTTP_BROKEN_AT_TAG,
                    path = pathPrefix,
                    message =
                        "REALITY + XHTTP combination is known broken at xray-core " +
                            "v26.1.18 or later; pick a different transport or downgrade upstreamTag.",
                ),
            )
        } else {
            emptyList()
        }
    }

    /**
     * Returns whether the supplied upstream tag is at or after the
     * v26.1.18 REALITY+XHTTP wire break. Tag comparison is best-effort
     * lexicographic on the version-number tuple (`v<a>.<b>.<c>`); tags
     * outside that shape default to "not broken" so the validator
     * stays permissive on unknown formats.
     */
    private fun isBrokenRealityXhttpTag(tag: String): Boolean {
        val parts = tag.removePrefix("v").split('.').mapNotNull { it.toLongOrNull() }
        if (parts.size < brokenRealityXhttpVersion.size) return false
        val firstDifference =
            brokenRealityXhttpVersion.indices.firstOrNull { parts[it] != brokenRealityXhttpVersion[it] }
        return firstDifference == null || parts[firstDifference] > brokenRealityXhttpVersion[firstDifference]
    }

    /** xray-core version (v26.1.18) at which the REALITY + XHTTP combination became wire-broken. */
    private val brokenRealityXhttpVersion: List<Long> = "26.1.18".split('.').map(String::toLong)
}
