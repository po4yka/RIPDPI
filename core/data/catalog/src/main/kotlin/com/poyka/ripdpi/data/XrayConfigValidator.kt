package com.poyka.ripdpi.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Provider validation policy: Vision is required for the supported TCP shape
 * and forbidden for XHTTP, certificate verification cannot be disabled, and
 * REALITY/XHTTP rejects the upstream regression window before its v26.2.6 fix.
 * Structural import validation belongs to the typed profile reader.
 */
object XrayConfigValidator {
    /**
     * Context for time- and version-dependent rules.
     *
     * @property upstreamTag the xray-core release tag the host-pack is
     *   targeting. Validation rules accept both release and Go module version formats.
     */
    data class Context(
        val upstreamTag: String,
    )

    enum class ErrorCode {
        PROFILE_INVALID,
        VLESS_FLOW_MISSING,
        VLESS_FLOW_UNSUPPORTED,
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
                errors += checkVlessFlow(outbound, streamSettings, pathPrefix)
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
        streamSettings: JsonObject?,
        pathPrefix: String,
    ): List<ValidationError> {
        val vnext = (outbound["settings"] as? JsonObject)?.get("vnext") as? JsonArray ?: return emptyList()
        val xhttp = (streamSettings?.get("network") as? JsonPrimitive)?.contentOrNull == "xhttp"
        val errors = mutableListOf<ValidationError>()
        vnext.forEachIndexed { vIndex, vElement ->
            val users = (vElement as? JsonObject)?.get("users") as? JsonArray ?: return@forEachIndexed
            users.forEachIndexed { uIndex, uElement ->
                val user = uElement as? JsonObject ?: return@forEachIndexed
                val flow = (user["flow"] as? JsonPrimitive)?.contentOrNull
                if (!xhttp && flow.isNullOrEmpty()) {
                    errors +=
                        ValidationError(
                            code = ErrorCode.VLESS_FLOW_MISSING,
                            path = "$pathPrefix.settings.vnext[$vIndex].users[$uIndex].flow",
                            message =
                                "VLESS TCP profile requires an explicit Vision flow.",
                        )
                } else if (unsupportedFlow(flow, xhttp)) {
                    errors +=
                        ValidationError(
                            code = ErrorCode.VLESS_FLOW_UNSUPPORTED,
                            path = "$pathPrefix.settings.vnext[$vIndex].users[$uIndex].flow",
                            message = "VLESS flow is incompatible with the selected transport.",
                        )
                }
            }
        }
        return errors
    }

    private fun unsupportedFlow(
        flow: String?,
        xhttp: Boolean,
    ): Boolean = if (xhttp) !flow.isNullOrEmpty() else flow !in setOf("xtls-rprx-vision", "xtls-rprx-vision-udp443")

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
                            "v26.1.18 through v26.2.5, or unverified build; use a verified fixed build.",
                ),
            )
        } else {
            emptyList()
        }
    }

    /** Release and Go-module tags identify the same known regression window.
     * Upstream fixed auto + REALITY in v26.2.6 (XTLS/Xray-core#5638).
     * Unknown builds are not evidence that the combination is safe.
     */
    private fun isBrokenRealityXhttpTag(tag: String): Boolean {
        val parts = tag.removePrefix("v").split('.').map { it.toIntOrNull() }
        if (parts.size != VersionParts || parts.any { it == null }) return true
        val version = if (parts[0] == 1) parts[1]!! else parts[0]!! * YearScale + parts[1]!! * MonthScale + parts[2]!!
        return version in BrokenRealityXhttpVersion until FixedRealityXhttpVersion
    }

    private const val VersionParts = 3
    private const val YearScale = 10_000
    private const val MonthScale = 100
    private const val BrokenRealityXhttpVersion = 260118
    private const val FixedRealityXhttpVersion = 260206
}
