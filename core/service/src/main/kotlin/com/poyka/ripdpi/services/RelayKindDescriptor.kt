package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RelayKindAnyTls
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindGoogleAppsScript
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindOff
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindShadowsocks
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVless
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayKindWebTunnel

/**
 * Where a relay kind sources the connection parameters it needs to start.
 */
internal enum class RelayConfigBacking {
    /** Parameters resolve through one or more *referenced* relay profile records. */
    PROFILE_BACKED,

    /** Parameters come from the relay kind's own inline relay-settings fields. */
    INLINE_SETTINGS_BACKED,
}

/**
 * Whether finalmask trailer masking is honoured for a relay kind.
 *
 * The descriptor records only the generic, `relay_kind`-keyed answer. The exact
 * activation decision is transport sub-mode dependent — VLESS Reality honours
 * finalmask only on its `xhttp` transport — so it deliberately stays in
 * [validateFinalmaskFeature] and the Rust `validate_finalmask_config` gate
 * rather than being driven off this enum.
 */
internal enum class RelayFinalmaskSupport {
    /** Finalmask is never honoured for this kind. */
    NONE,

    /** Finalmask is honoured on one transport sub-mode only (VLESS Reality xHTTP). */
    SUB_MODE_DEPENDENT,

    /** Finalmask is honoured on every transport this kind uses. */
    SUPPORTED,
}

/**
 * Static, `relay_kind`-keyed metadata for one relay transport — the explicit
 * platform a new relay kind is added through.
 *
 * This is the Kotlin-side mirror of the Rust `RelayTransportDescriptor` table
 * in `native/rust/crates/ripdpi-relay-core/src/transport_descriptor.rs`. The
 * generic capability fields ([tcp], [udp], [reusable], [supportsOutboundBindIp])
 * carry the same values as the matching Rust descriptor row for every kind that
 * has a native Rust backend; `RelayKindDescriptorDriftTest` pins that alignment.
 *
 * Kotlin owns four facts the Rust table does not model: [subprocessBacked],
 * [finalmaskSupport], [configBacking], and the Kotlin-only pluggable-transport
 * and Apps Script kinds that have no native Rust backend at all.
 *
 * Sub-mode-specific behaviour (VLESS xHTTP, finalmask activation, the Cloudflare
 * publish-local-origin subprocess) deliberately stays in the relay resolvers
 * until the typed-submode refactor in `FEATURE_EXTENSION_GUIDE.md` §2.
 */
internal data class RelayKindDescriptor(
    /** The stable `relay_kind` string (`app_settings.proto` field 171); a wire contract. */
    val kindId: String,
    /** Human-readable label for diagnostics and selection surfaces. */
    val label: String,
    /** The transport relays TCP `CONNECT` traffic. */
    val tcp: Boolean,
    /** The transport relays UDP `ASSOCIATE` traffic. */
    val udp: Boolean,
    /** Backend connections can be pooled and reused across SOCKS sessions. */
    val reusable: Boolean,
    /** The transport honours a relay `outbound_bind_ip`. */
    val supportsOutboundBindIp: Boolean,
    /** The relay runs as an external subprocess rather than the in-process Rust core. */
    val subprocessBacked: Boolean,
    /** Whether finalmask masking is honoured — see [RelayFinalmaskSupport]. */
    val finalmaskSupport: RelayFinalmaskSupport,
    /** Whether the kind resolves through referenced profiles or its inline settings. */
    val configBacking: RelayConfigBacking,
)

/**
 * Inventory of every relay kind the app recognises, one row per stable
 * `relay_kind` string and ordered to match the `relay_kind` comment in
 * `app_settings.proto`.
 *
 * Keep this aligned with the Rust descriptor table, that protobuf comment, and
 * the resolver registrations built in [buildRelayKindRegistrations];
 * `RelayKindDescriptorDriftTest` enforces all three alignments.
 */
internal val RelayKindDescriptors: List<RelayKindDescriptor> =
    listOf(
        RelayKindDescriptor(
            kindId = RelayKindOff,
            label = "Off",
            tcp = false,
            udp = false,
            reusable = false,
            supportsOutboundBindIp = false,
            subprocessBacked = false,
            finalmaskSupport = RelayFinalmaskSupport.NONE,
            configBacking = RelayConfigBacking.INLINE_SETTINGS_BACKED,
        ),
        RelayKindDescriptor(
            kindId = RelayKindVless,
            label = "VLESS (TLS)",
            tcp = true,
            udp = false,
            reusable = false,
            supportsOutboundBindIp = true,
            subprocessBacked = false,
            finalmaskSupport = RelayFinalmaskSupport.NONE,
            configBacking = RelayConfigBacking.INLINE_SETTINGS_BACKED,
        ),
        RelayKindDescriptor(
            kindId = RelayKindVlessReality,
            label = "VLESS Reality",
            tcp = true,
            udp = false,
            reusable = false,
            supportsOutboundBindIp = true,
            subprocessBacked = false,
            finalmaskSupport = RelayFinalmaskSupport.SUB_MODE_DEPENDENT,
            configBacking = RelayConfigBacking.INLINE_SETTINGS_BACKED,
        ),
        RelayKindDescriptor(
            kindId = RelayKindHysteria2,
            label = "Hysteria2",
            tcp = true,
            udp = true,
            reusable = true,
            supportsOutboundBindIp = false,
            subprocessBacked = false,
            finalmaskSupport = RelayFinalmaskSupport.NONE,
            configBacking = RelayConfigBacking.INLINE_SETTINGS_BACKED,
        ),
        RelayKindDescriptor(
            kindId = RelayKindChainRelay,
            label = "Chain relay",
            tcp = true,
            udp = false,
            reusable = false,
            supportsOutboundBindIp = true,
            subprocessBacked = false,
            finalmaskSupport = RelayFinalmaskSupport.NONE,
            configBacking = RelayConfigBacking.PROFILE_BACKED,
        ),
        RelayKindDescriptor(
            kindId = RelayKindMasque,
            label = "MASQUE",
            tcp = true,
            udp = true,
            reusable = true,
            supportsOutboundBindIp = false,
            subprocessBacked = false,
            finalmaskSupport = RelayFinalmaskSupport.NONE,
            configBacking = RelayConfigBacking.INLINE_SETTINGS_BACKED,
        ),
        RelayKindDescriptor(
            kindId = RelayKindAnyTls,
            label = "AnyTLS",
            tcp = true,
            udp = true,
            reusable = true,
            supportsOutboundBindIp = true,
            subprocessBacked = false,
            finalmaskSupport = RelayFinalmaskSupport.NONE,
            configBacking = RelayConfigBacking.INLINE_SETTINGS_BACKED,
        ),
        RelayKindDescriptor(
            kindId = RelayKindCloudflareTunnel,
            label = "Cloudflare Tunnel",
            tcp = true,
            udp = false,
            reusable = true,
            supportsOutboundBindIp = true,
            subprocessBacked = false,
            finalmaskSupport = RelayFinalmaskSupport.SUPPORTED,
            configBacking = RelayConfigBacking.INLINE_SETTINGS_BACKED,
        ),
        RelayKindDescriptor(
            kindId = RelayKindTuicV5,
            label = "TUIC v5",
            tcp = true,
            udp = true,
            reusable = true,
            supportsOutboundBindIp = true,
            subprocessBacked = false,
            finalmaskSupport = RelayFinalmaskSupport.NONE,
            configBacking = RelayConfigBacking.INLINE_SETTINGS_BACKED,
        ),
        RelayKindDescriptor(
            kindId = RelayKindShadowTlsV3,
            label = "ShadowTLS v3",
            tcp = true,
            udp = false,
            reusable = false,
            supportsOutboundBindIp = true,
            subprocessBacked = false,
            finalmaskSupport = RelayFinalmaskSupport.NONE,
            configBacking = RelayConfigBacking.PROFILE_BACKED,
        ),
        RelayKindDescriptor(
            kindId = RelayKindTrojan,
            label = "Trojan",
            tcp = true,
            udp = true,
            reusable = false,
            supportsOutboundBindIp = true,
            subprocessBacked = false,
            finalmaskSupport = RelayFinalmaskSupport.NONE,
            configBacking = RelayConfigBacking.INLINE_SETTINGS_BACKED,
        ),
        RelayKindDescriptor(
            kindId = RelayKindShadowsocks,
            label = "Shadowsocks",
            tcp = true,
            udp = true,
            reusable = false,
            supportsOutboundBindIp = true,
            subprocessBacked = false,
            finalmaskSupport = RelayFinalmaskSupport.NONE,
            configBacking = RelayConfigBacking.INLINE_SETTINGS_BACKED,
        ),
        RelayKindDescriptor(
            kindId = RelayKindNaiveProxy,
            label = "NaiveProxy",
            tcp = true,
            udp = false,
            reusable = false,
            supportsOutboundBindIp = true,
            subprocessBacked = true,
            finalmaskSupport = RelayFinalmaskSupport.NONE,
            configBacking = RelayConfigBacking.INLINE_SETTINGS_BACKED,
        ),
        RelayKindDescriptor(
            kindId = RelayKindGoogleAppsScript,
            label = "Google Apps Script",
            tcp = true,
            udp = false,
            reusable = false,
            supportsOutboundBindIp = false,
            subprocessBacked = false,
            finalmaskSupport = RelayFinalmaskSupport.NONE,
            configBacking = RelayConfigBacking.INLINE_SETTINGS_BACKED,
        ),
        RelayKindDescriptor(
            kindId = RelayKindSnowflake,
            label = "Snowflake",
            tcp = true,
            udp = false,
            reusable = false,
            supportsOutboundBindIp = false,
            subprocessBacked = true,
            finalmaskSupport = RelayFinalmaskSupport.NONE,
            configBacking = RelayConfigBacking.INLINE_SETTINGS_BACKED,
        ),
        RelayKindDescriptor(
            kindId = RelayKindWebTunnel,
            label = "WebTunnel",
            tcp = true,
            udp = false,
            reusable = false,
            supportsOutboundBindIp = false,
            subprocessBacked = true,
            finalmaskSupport = RelayFinalmaskSupport.NONE,
            configBacking = RelayConfigBacking.INLINE_SETTINGS_BACKED,
        ),
        RelayKindDescriptor(
            kindId = RelayKindObfs4,
            label = "obfs4",
            tcp = true,
            udp = false,
            reusable = false,
            supportsOutboundBindIp = false,
            subprocessBacked = true,
            finalmaskSupport = RelayFinalmaskSupport.NONE,
            configBacking = RelayConfigBacking.INLINE_SETTINGS_BACKED,
        ),
    )

private val RelayKindDescriptorsByKind: Map<String, RelayKindDescriptor> =
    RelayKindDescriptors.associateBy(RelayKindDescriptor::kindId)

/**
 * The [RelayKindDescriptor] for [kindId], or `null` for an unrecognised relay
 * kind. Callers map a `null` to the historical permissive / off-state default.
 */
internal fun relayKindDescriptor(kindId: String): RelayKindDescriptor? = RelayKindDescriptorsByKind[kindId]
