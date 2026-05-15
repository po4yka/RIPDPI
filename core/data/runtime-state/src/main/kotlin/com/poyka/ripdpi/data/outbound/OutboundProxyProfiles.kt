package com.poyka.ripdpi.data.outbound

import kotlinx.serialization.Serializable

/**
 * A string wrapper whose [toString] always returns `"<redacted>"` so that
 * credentials never appear in logs, crash reports, or diagnostic surfaces.
 *
 * The raw value is accessible via [value] for serialization and protocol use only.
 */
@Serializable
@JvmInline
value class Redacted(
    val value: String,
) {
    override fun toString(): String = "<redacted>"
}

/**
 * Runtime profile for a SOCKS5 outbound proxy node.
 *
 * - [username] / [password] are wrapped in [Redacted] so they cannot leak via
 *   `toString` (logs, crash reports, diagnostic surfaces).
 * - Both fields are nullable: `null` means unauthenticated (no-auth method
 *   `0x00` only); non-null means username/password method `0x02` is offered.
 * - UDP ASSOCIATE is out of scope for v1; this profile covers CONNECT only.
 */
@Serializable
data class Socks5OutboundRuntimeState(
    val server: String,
    val port: Int,
    val username: Redacted? = null,
    val password: Redacted? = null,
)

/**
 * Runtime profile for an HTTP CONNECT outbound proxy node.
 *
 * - [username] / [password] are [Redacted] for the same reason as in
 *   [Socks5OutboundRuntimeState].
 * - [tls] controls whether the connection to the proxy itself is TLS-wrapped
 *   (i.e. an HTTPS proxy).
 * - [sni] is the TLS SNI override used when [tls] is `true`; `null` means
 *   use [server] as the SNI.
 */
@Serializable
data class HttpProxyOutboundRuntimeState(
    val server: String,
    val port: Int,
    val username: Redacted? = null,
    val password: Redacted? = null,
    val tls: Boolean = false,
    val sni: String? = null,
)
