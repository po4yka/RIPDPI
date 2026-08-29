package com.poyka.ripdpi.data.xray

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed client profile for the embedded Xray provider.
 *
 * Covers the minimal set of fields needed to bring up a VLESS/REALITY or
 * VLESS/XHTTP outbound through a local SOCKS inbound (the
 * [com.poyka.ripdpi.data.xray.XrayTunnelTopology.TunToLocalInbound] topology).
 * This is a product-safe surface: only the fields RIPDPI knows how to render
 * and validate are modelled, so the renderer never emits arbitrary xray-core
 * JSON.
 *
 * The model is [Serializable] so profiles can be persisted via the same
 * kotlinx.serialization stack the rest of `:core:data` uses. Secrets in this
 * model ([Outbound.uuid], [Reality.privateKey] when present) MUST be scrubbed
 * before any diagnostic/log emission — see
 * [com.poyka.ripdpi.data.xray.XrayProfileRedactor].
 *
 * Tracks the completed `render-validated-xray-client-configs` task (see git history).
 */
@Serializable
data class XrayProfile(
    /** Human-readable label for the profile (not rendered into the config). */
    val name: String,
    /** The single outbound this profile drives. */
    val outbound: Outbound,
    /** Local inbound + protect/DNS knobs for the rendered config. */
    val inbound: LocalInbound = LocalInbound(),
    val dns: DnsSettings = DnsSettings(),
) {
    /**
     * Stream-layer security selector. VLESS over REALITY or plain TLS are the
     * two shapes RIPDPI renders today; the validator
     * ([XrayConfigValidator.ErrorCode.ALLOW_INSECURE_DISABLED]) guards the TLS
     * path against `allowInsecure`.
     */
    @Serializable
    enum class Security {
        @SerialName("reality")
        REALITY,

        @SerialName("tls")
        TLS,
    }

    /**
     * Stream-layer transport. `tcp` pairs with REALITY/Vision; `xhttp` is the
     * masquerading transport. REALITY+XHTTP is version-gated by the validator
     * ([XrayConfigValidator.ErrorCode.REALITY_XHTTP_BROKEN_AT_TAG]).
     */
    @Serializable
    enum class Network {
        @SerialName("tcp")
        TCP,

        @SerialName("xhttp")
        XHTTP,
    }

    @Serializable
    data class Outbound(
        /** Server address (domain or IP). Redacted in diagnostics. */
        val serverAddress: String,
        val serverPort: Int,
        /** VLESS user UUID. Secret — redacted in diagnostics. */
        val uuid: String,
        val security: Security,
        val network: Network,
        /**
         * Vision requires a direct TLS/REALITY connection. XHTTP carries VLESS
         * without Vision; its HTTP stream cannot be used as a Vision connection.
         */
        val flow: String = if (network == Network.XHTTP) "" else "xtls-rprx-vision",
        /** REALITY parameters; required when [security] is [Security.REALITY]. */
        val reality: Reality? = null,
        /** TLS parameters; required when [security] is [Security.TLS]. */
        val tls: Tls? = null,
        /** XHTTP parameters; required when [network] is [Network.XHTTP]. */
        val xhttp: Xhttp? = null,
    )

    /**
     * REALITY handshake parameters.
     *
     * [privateKey] is only present for server-side configs; a client profile
     * normally carries [publicKey]. The field is modelled so the redactor can
     * scrub it if a server profile ever flows through diagnostics.
     */
    @Serializable
    data class Reality(
        /** REALITY x25519 public key (client side). Redacted in diagnostics. */
        val publicKey: String,
        /** SNI presented to the censor / fronting target. */
        val serverName: String,
        val shortId: String = "",
        /** uTLS fingerprint, e.g. `chrome`. */
        val fingerprint: String = "chrome",
        /** REALITY x25519 private key (server side only). Redacted in diagnostics. */
        val privateKey: String? = null,
    )

    @Serializable
    data class Tls(
        val serverName: String,
        val fingerprint: String = "chrome",
        /**
         * Must stay `false`: xray-core auto-disables `allowInsecure` from
         * 2026-06-01 and the validator rejects `true`
         * ([XrayConfigValidator.ErrorCode.ALLOW_INSECURE_DISABLED]).
         */
        val allowInsecure: Boolean = false,
    )

    @Serializable
    data class Xhttp(
        val path: String = "/",
        /** XHTTP mode: `auto`, `packet-up`, `stream-up`, `stream-one`. */
        val mode: String = "auto",
        /** Optional `Host` header override. */
        val host: String = "",
    )

    /**
     * Local SOCKS inbound the TUN forwarder dials. Defaults match
     * [com.poyka.ripdpi.data.xray.XrayProviderConfig.localInboundPort].
     */
    @Serializable
    data class LocalInbound(
        val listen: String = "127.0.0.1",
        val port: Int = DEFAULT_LOCAL_INBOUND_PORT,
        /** Enable UDP relay through the SOCKS inbound (needed for QUIC/DNS). */
        val udpEnabled: Boolean = true,
    )

    @Serializable
    data class DnsSettings(
        /** Upstream DNS servers Xray resolves through. */
        val servers: List<String> = listOf("1.1.1.1", "8.8.8.8"),
        /**
         * Bypass tag for direct (un-proxied) traffic; the routing rule sends
         * DNS-loopback traffic here so it does not re-enter the tunnel.
         */
        val queryStrategy: String = "UseIP",
    )

    companion object {
        /** Mirrors [com.poyka.ripdpi.data.xray.XrayProviderConfig.localInboundPort]. */
        const val DEFAULT_LOCAL_INBOUND_PORT: Int = 10808
    }
}
