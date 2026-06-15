package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ProviderRoute
import com.poyka.ripdpi.data.xray.DurableXrayProfileStore
import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.data.xray.XrayConfigRenderer
import com.poyka.ripdpi.data.xray.XrayConfigValidationFinding
import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayProviderConfig
import com.poyka.ripdpi.serialization.RipDpiEncodeDefaultsJson
import kotlinx.serialization.json.JsonObject

/**
 * Builds the [ProviderRoute] and the rendered xray-core JSON config for the
 * active Xray provider session, from the DURABLE profile store (decision 3).
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
    private val profileStore: DurableXrayProfileStore,
    private val renderer: XrayConfigRenderer = XrayConfigRenderer(),
) {
    /** Routing tag the renderer validates the proxied path against. */
    private val proxyTag = "proxy"

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

        /** No durable profile is persisted for [profileId]. */
        data object NoProfile : Result
    }

    /**
     * Load [profileId] from the durable store, render + validate its config, and
     * return a [Result]. Suspends only for the store read; the render itself is
     * pure. An invalid config is reported as findings, never as a renderable or
     * thrown secret.
     */
    suspend fun build(profileId: String): Result {
        val profile = profileStore.load(profileId) ?: return Result.NoProfile
        return when (val rendered = renderer.render(profile, upstreamTag = proxyTag)) {
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

    private fun routeFor(profile: XrayProfile): ProviderRoute =
        ProviderRoute(
            kind = VpnProviderKind.Xray,
            xrayConfig = XrayProviderConfig(localInboundPort = profile.inbound.port),
        )

    private fun serialize(config: JsonObject): String = configJson.encodeToString(JsonObject.serializer(), config)
}
