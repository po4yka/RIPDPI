package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ProviderRoute
import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.data.xray.XrayConfigRenderer
import com.poyka.ripdpi.data.xray.XrayConfigValidationFinding
import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayProviderBuildInfo
import com.poyka.ripdpi.data.xray.XrayProviderConfig
import com.poyka.ripdpi.serialization.RipDpiEncodeDefaultsJson
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import java.net.URI

/**
 * Builds the [ProviderRoute] and the rendered xray-core JSON config for the
 * active Xray provider session, from one recovered durable profile snapshot.
 *
 * ### Secret discipline
 * The rendered config is secret-bearing (it embeds the VLESS UUID and REALITY
 * keys reconstituted from the Keystore secret half). This builder therefore:
 * - NEVER puts the rendered config into a [Rejected] result or any thrown
 *   message — a rejection carries only the typed [XrayConfigValidationFinding]
 *   list (already free of config content).
 * - Returns the rendered config as an opaque string only inside [Resolved], to
 *   be handed straight to [com.poyka.ripdpi.core.RipDpiXrayRuntime.start] and
 *   never retained, logged, or telemetered.
 *
 * The route's inbound port is taken from the profile's local inbound so the
 * tunnel upstream ([com.poyka.ripdpi.core.XrayTunnelHandoff]) and the rendered
 * inbound always agree.
 */
internal class XrayProviderRouteBuilder(
    private val resolveEndpoint: suspend (String) -> List<String>,
    private val renderer: XrayConfigRenderer = XrayConfigRenderer(),
    private val upstreamTag: String = XrayProviderBuildInfo.upstreamTag,
) {
    // Deterministic, default-preserving JSON so the rendered config matches what
    // the validator consumed; no pretty-print to keep the wire payload compact.
    // Reuse the centralized instance per the serialization-source rule.
    private val configJson = RipDpiEncodeDefaultsJson

    sealed interface Result {
        /**
         * Route + rendered (secret-bearing) config ready for the orchestrator.
         *
         * @property route the provider route (kind + inbound port + topology).
         * @property renderedConfig opaque secret-bearing JSON; do not log/retain.
         */
        data class Resolved(
            val route: ProviderRoute,
            val renderedConfig: String,
        ) : Result

        /** Profile present but config rejected; carries only typed findings. */
        data class Rejected(
            val findings: List<XrayConfigValidationFinding>,
            val testError: String? = null,
        ) : Result

        /** No durable profile was present in the selected snapshot. */
        data object NoProfile : Result
    }

    /**
     * Render and validate [profile] after releasing the journal read lock.
     * Return a [Result]. Suspends only for relay endpoint bootstrap;
     * rendering is pure. An invalid config is reported as findings, never as a renderable or
     * thrown secret.
     */
    suspend fun build(profile: XrayProfile?): Result {
        if (profile == null) return Result.NoProfile
        // Validate before any DNS I/O; use a transient copy so durable identity never changes.
        val initial = renderer.render(profile, upstreamTag = upstreamTag)
        val prepared = if (initial is XrayConfigRenderer.Result.Success) resolveProfileEndpoint(profile) else profile
        return when (val rendered = renderer.render(prepared, upstreamTag = upstreamTag)) {
            is XrayConfigRenderer.Result.Success -> {
                Result.Resolved(
                    route = routeFor(profile),
                    renderedConfig = serialize(rendered.config),
                )
            }

            is XrayConfigRenderer.Result.Rejected -> {
                Result.Rejected(
                    findings = rendered.validationErrors.map(XrayConfigValidationFinding::from),
                    testError = rendered.testError,
                )
            }
        }
    }

    private suspend fun resolveProfileEndpoint(profile: XrayProfile): XrayProfile {
        val outbound = profile.outbound
        val original = outbound.serverAddress
        if (isNumeric(original)) return profile
        val address =
            resolveEndpoint(original).firstOrNull { isNumeric(it) }
                ?: error("Xray relay bootstrap returned no numeric address")
        currentCoroutineContext().ensureActive()
        val tls =
            if (outbound.security == XrayProfile.Security.TLS) {
                (outbound.tls ?: XrayProfile.Tls(serverName = original)).let {
                    if (it.serverName.isBlank()) it.copy(serverName = original) else it
                }
            } else {
                outbound.tls
            }
        val reality = outbound.reality?.let { if (it.serverName.isBlank()) it.copy(serverName = original) else it }
        val serverName =
            when (outbound.security) {
                XrayProfile.Security.TLS -> tls?.serverName
                XrayProfile.Security.REALITY -> reality?.serverName
            } ?: original
        val xhttp =
            outbound.xhttp?.let {
                if (it.host.isBlank()) it.copy(host = serverName) else it
            }
        return profile.copy(
            outbound = outbound.copy(serverAddress = address, tls = tls, reality = reality, xhttp = xhttp),
        )
    }

    private fun isNumeric(host: String): Boolean {
        if (':' in host && host.all { it in "0123456789abcdefABCDEF:." }) {
            // URI validates bracketed IPv6 syntax without calling a DNS resolver.
            return runCatching { URI("http://[$host]").host != null }.getOrDefault(false)
        }
        val parts = host.split('.')
        return parts.size == Ipv4OctetCount &&
            parts.all { it.isNotEmpty() && it.all(Char::isDigit) && it.toIntOrNull() in 0..Ipv4MaxOctet }
    }

    private fun routeFor(profile: XrayProfile): ProviderRoute =
        ProviderRoute(
            kind = VpnProviderKind.Xray,
            xrayConfig = XrayProviderConfig(localInboundPort = profile.inbound.port),
        )

    private fun serialize(config: JsonObject): String = configJson.encodeToString(JsonObject.serializer(), config)

    private companion object {
        const val Ipv4OctetCount = 4
        const val Ipv4MaxOctet = 255
    }
}
