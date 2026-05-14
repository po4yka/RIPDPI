package com.poyka.ripdpi.data.wireguard

import kotlinx.serialization.Serializable

/**
 * A complete, shareable AmneziaWG profile: the per-device WireGuard identity
 * plus the AmneziaWG obfuscation field set.
 *
 * This is the model the `amneziawg://` share URI
 * ([com.poyka.ripdpi.data.uri.AmneziaWgUriCodec]) encodes and decodes. Unlike
 * [AmneziaWgConfig] — which mirrors the `.conf` INI structure with separate
 * `[Interface]` / `[Peer]` sections — this is a flattened single-peer shape
 * because a share URI carries exactly one endpoint.
 *
 * - [privateKey] / [publicKey] / [presharedKey] are base64 key material exactly
 *   as they appear in a `.conf`.
 * - [allowedIps] and [dns] are CIDR / IP lists.
 * - [awg] carries the obfuscation parameters; an empty [AmneziaWgParameters]
 *   means a vanilla WireGuard peer shared over the AmneziaWG scheme.
 */
@Serializable
data class AmneziaWgProfile(
    val name: String,
    val host: String,
    val port: Int,
    val privateKey: String,
    val publicKey: String,
    val presharedKey: String? = null,
    val allowedIps: List<String> = emptyList(),
    val dns: List<String> = emptyList(),
    val mtu: Int? = null,
    val awg: AmneziaWgParameters = AmneziaWgParameters(),
)
