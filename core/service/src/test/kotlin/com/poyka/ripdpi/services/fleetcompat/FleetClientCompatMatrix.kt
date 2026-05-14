package com.poyka.ripdpi.services.fleetcompat

/*
 * Client-compatibility regression matrix for RIPDPI fleet profiles.
 *
 * Different VPN clients parse subscriptions, routing policy, DNS, TUN, IPv6,
 * and core versions differently. This matrix is the single, machine-checked
 * source of truth for which compatibility dimensions every supported client
 * must be exercised against, and for the hard invariants that distinguish a
 * full-policy client from a nodes-only one.
 *
 * It is pure data plus pure predicates -- no Android, no I/O -- so the
 * accompanying FleetClientCompatMatrixTest can assert structural completeness
 * and the per-family invariants on the JVM. A client or a required dimension
 * silently dropped from the fleet release gate makes that test go red.
 *
 * This deliberately mirrors the phase-1/2 golden-file harness
 * (core/data/.../fleet/FleetCompatHarness.kt): that harness locks the bundle
 * import surface; this matrix locks the client coverage surface that sits on
 * top of it.
 */

/** A supported VPN client and the engine family it belongs to. */
enum class FleetClient(
    val displayName: String,
    val family: ClientFamily,
) {
    CUSTOM_ANDROID("RIPDPI custom Android", ClientFamily.CUSTOM_ANDROID),
    SINGBOX_SFA("sing-box / SFA", ClientFamily.SINGBOX),
    V2RAYNG("v2rayNG", ClientFamily.V2RAY_ANDROID),
    NEKOBOX("NekoBox", ClientFamily.NODES_ONLY),
    HUSI("husi", ClientFamily.NODES_ONLY),
    STREISAND_V2BOX("Streisand / V2Box (iOS)", ClientFamily.IOS),
    V2RAYN("v2rayN (Windows)", ClientFamily.V2RAY_DESKTOP),
    SINGBOX_CLI("sing-box CLI", ClientFamily.SINGBOX),
}

/** Coarse engine family; drives the invariants in [FleetClientCompatMatrix]. */
enum class ClientFamily {
    CUSTOM_ANDROID,
    SINGBOX,
    V2RAY_ANDROID,
    V2RAY_DESKTOP,
    NODES_ONLY,
    IOS,
}

/**
 * A single compatibility dimension every client is regression-tested against.
 *
 * [SHARED] dimensions apply to every client; the family-specific ones apply
 * only to the clients whose [FleetClient.family] matches.
 */
enum class CompatDimension(
    val appliesTo: DimensionScope,
) {
    // --- shared matrix (every client) ---
    IMPORT(DimensionScope.SHARED),
    CREDENTIAL_SCOPE(DimensionScope.SHARED),
    SELECTOR(DimensionScope.SHARED),
    URLTEST(DimensionScope.SHARED),
    TUN(DimensionScope.SHARED),
    KILL_SWITCH(DimensionScope.SHARED),
    DNS(DimensionScope.SHARED),
    IPV6(DimensionScope.SHARED),
    NETWORK_TRANSITIONS(DimensionScope.SHARED),
    REVOCATION(DimensionScope.SHARED),
    LOGS(DimensionScope.SHARED),
    UPDATE_MIGRATION(DimensionScope.SHARED),

    // --- sing-box / SFA ---
    SINGBOX_CONFIG_CHECK(DimensionScope.SINGBOX),
    SINGBOX_DEGRADED_CLOUDFLARE_EXCLUSION(DimensionScope.SINGBOX),
    SINGBOX_STRICT_ROUTE(DimensionScope.SINGBOX),
    SINGBOX_DNS_HIJACK(DimensionScope.SINGBOX),
    SINGBOX_RULE_SET_UPDATE(DimensionScope.SINGBOX),
    SINGBOX_REVOKED_PROFILE_REMOVAL(DimensionScope.SINGBOX),

    // --- v2rayNG ---
    V2RAYNG_VLESS_REALITY_URI_IMPORT(DimensionScope.V2RAY_ANDROID),
    V2RAYNG_PER_DEVICE_SUBSCRIPTION(DimensionScope.V2RAY_ANDROID),
    V2RAYNG_VPN_MODE(DimensionScope.V2RAY_ANDROID),
    V2RAYNG_ANDROID_LOCKDOWN(DimensionScope.V2RAY_ANDROID),
    V2RAYNG_DNS_IPV6_LEAK_CHECK(DimensionScope.V2RAY_ANDROID),
    V2RAYNG_CORE_UPDATE(DimensionScope.V2RAY_ANDROID),
    V2RAYNG_HYSTERIA2_FALLBACK(DimensionScope.V2RAY_ANDROID),

    // --- NekoBox / husi (nodes-only) ---
    NODES_ONLY_SUBSCRIPTION_IS_NODES_ONLY(DimensionScope.NODES_ONLY),
    NODES_ONLY_ROUTING_VERIFIED_SEPARATELY(DimensionScope.NODES_ONLY),
    NODES_ONLY_DNS_VERIFIED_SEPARATELY(DimensionScope.NODES_ONLY),

    // --- iOS (Streisand / V2Box) ---
    IOS_URI_SUBSCRIPTION_IMPORT(DimensionScope.IOS),
    IOS_MANUAL_FALLBACK(DimensionScope.IOS),
    IOS_DNS_IPV6_LEAK(DimensionScope.IOS),
    IOS_SLEEP_WAKE(DimensionScope.IOS),
    IOS_WIFI_LTE(DimensionScope.IOS),
    IOS_APP_UPDATE_PERSISTENCE(DimensionScope.IOS),

    // --- v2rayN (Windows desktop) ---
    V2RAYN_XRAY_CORE(DimensionScope.V2RAY_DESKTOP),
    V2RAYN_SINGBOX_CORE_IF_USED(DimensionScope.V2RAY_DESKTOP),
    V2RAYN_TUN_ELEVATION(DimensionScope.V2RAY_DESKTOP),
    V2RAYN_WINDOWS_FIREWALL_KILL_SWITCH(DimensionScope.V2RAY_DESKTOP),
    V2RAYN_DNS_IPV6_LEAK(DimensionScope.V2RAY_DESKTOP),
    V2RAYN_CORE_UPDATE(DimensionScope.V2RAY_DESKTOP),

    // --- custom Android (RIPDPI VpnService) ---
    CUSTOM_VPNSERVICE_LIFECYCLE(DimensionScope.CUSTOM_ANDROID),
    CUSTOM_PROTECT_TUNNEL_SOCKETS(DimensionScope.CUSTOM_ANDROID),
    CUSTOM_ON_REVOKE(DimensionScope.CUSTOM_ANDROID),
    CUSTOM_VIRTUAL_DNS(DimensionScope.CUSTOM_ANDROID),
    CUSTOM_NO_ALLOW_BYPASS(DimensionScope.CUSTOM_ANDROID),
    CUSTOM_IPV4_ONLY_BEHAVIOR(DimensionScope.CUSTOM_ANDROID),
    CUSTOM_PACKAGE_VISIBILITY(DimensionScope.CUSTOM_ANDROID),
    CUSTOM_FOREGROUND_SERVICE_RESILIENCE(DimensionScope.CUSTOM_ANDROID),
    CUSTOM_NO_LOG_SECRETS(DimensionScope.CUSTOM_ANDROID),
    CUSTOM_PROFILE_SIGNATURE_EXPIRY_REVOCATION(DimensionScope.CUSTOM_ANDROID),
}

/** Whether a [CompatDimension] is shared by all clients or scoped to one family. */
enum class DimensionScope {
    SHARED,
    SINGBOX,
    V2RAY_ANDROID,
    V2RAY_DESKTOP,
    NODES_ONLY,
    IOS,
    CUSTOM_ANDROID,
    ;

    companion object {
        /** Maps a [ClientFamily] to the family-specific [DimensionScope] it owns. */
        fun forFamily(family: ClientFamily): DimensionScope =
            when (family) {
                ClientFamily.CUSTOM_ANDROID -> CUSTOM_ANDROID
                ClientFamily.SINGBOX -> SINGBOX
                ClientFamily.V2RAY_ANDROID -> V2RAY_ANDROID
                ClientFamily.V2RAY_DESKTOP -> V2RAY_DESKTOP
                ClientFamily.NODES_ONLY -> NODES_ONLY
                ClientFamily.IOS -> IOS
            }
    }
}

/**
 * One regression cell: a (client, dimension) pair, plus the *recorded*
 * compatibility expectation for it.
 *
 * @param recordsAppVersion true when this cell's run must capture the client
 *   app version (every cell must — see the matrix invariant).
 * @param recordsCoreVersion true when this cell's run must capture the
 *   embedded core version.
 */
data class CompatCell(
    val client: FleetClient,
    val dimension: CompatDimension,
    val recordsAppVersion: Boolean,
    val recordsCoreVersion: Boolean,
)

/** Structural problems found while validating the matrix. */
data class MatrixValidationIssue(
    val client: FleetClient,
    val message: String,
)

/**
 * The assembled matrix: every applicable [CompatCell] for every [FleetClient],
 * plus the predicates the regression test asserts.
 */
object FleetClientCompatMatrix {
    /** Dimensions every client is exercised against, regardless of family. */
    val sharedDimensions: List<CompatDimension> =
        CompatDimension.entries.filter { it.appliesTo == DimensionScope.SHARED }

    /** Family-specific dimensions for the given [family]. */
    fun familyDimensions(family: ClientFamily): List<CompatDimension> {
        val scope = DimensionScope.forFamily(family)
        return CompatDimension.entries.filter { it.appliesTo == scope }
    }

    /** Every dimension a single [client] must be regression-tested against. */
    fun dimensionsFor(client: FleetClient): List<CompatDimension> = sharedDimensions + familyDimensions(client.family)

    /** The full set of cells for [client]. */
    fun cellsFor(client: FleetClient): List<CompatCell> =
        dimensionsFor(client).map { dimension ->
            CompatCell(
                client = client,
                dimension = dimension,
                // Compatibility tests must record both the app version and the
                // embedded core version for every cell.
                recordsAppVersion = true,
                recordsCoreVersion = true,
            )
        }

    /** Every cell across every supported client. */
    fun allCells(): List<CompatCell> = FleetClient.entries.flatMap(::cellsFor)

    /**
     * True when [client] must treat a subscription as nodes-only — i.e. it may
     * import outbound nodes but must NOT silently apply route/DNS policy from
     * the subscription; routing and DNS are verified separately.
     */
    fun isNodesOnly(client: FleetClient): Boolean = client.family == ClientFamily.NODES_ONLY

    /**
     * True when [client] is a full-policy client: it imports and applies
     * routing, DNS, TUN, and kill-switch policy from the fleet profile.
     */
    fun isFullPolicy(client: FleetClient): Boolean = !isNodesOnly(client)

    /**
     * Validates the matrix invariants and returns every problem found.
     * An empty list means the matrix is structurally sound.
     */
    fun validate(): List<MatrixValidationIssue> {
        val issues = mutableListOf<MatrixValidationIssue>()
        FleetClient.entries.forEach { client ->
            val dimensions = dimensionsFor(client)
            val cells = cellsFor(client)

            if (!dimensions.containsAll(sharedDimensions)) {
                issues += MatrixValidationIssue(client, "missing one or more shared dimensions")
            }
            if (familyDimensions(client.family).isEmpty()) {
                issues += MatrixValidationIssue(client, "has no family-specific dimensions")
            }
            if (dimensions.size != dimensions.toSet().size) {
                issues += MatrixValidationIssue(client, "duplicate dimensions in coverage list")
            }
            if (cells.any { !it.recordsAppVersion || !it.recordsCoreVersion }) {
                issues += MatrixValidationIssue(client, "a cell fails to record app/core versions")
            }

            // Nodes-only clients must have the nodes-only guard dimensions and
            // must NOT be mislabelled as full-policy.
            if (isNodesOnly(client)) {
                val nodesOnlyGuards =
                    listOf(
                        CompatDimension.NODES_ONLY_SUBSCRIPTION_IS_NODES_ONLY,
                        CompatDimension.NODES_ONLY_ROUTING_VERIFIED_SEPARATELY,
                        CompatDimension.NODES_ONLY_DNS_VERIFIED_SEPARATELY,
                    )
                if (!dimensions.containsAll(nodesOnlyGuards)) {
                    issues += MatrixValidationIssue(client, "nodes-only client missing nodes-only guard dimensions")
                }
            }

            // The custom Android client carries the fail-closed VpnService
            // invariants; if those are dropped the policy engine is unguarded.
            if (client == FleetClient.CUSTOM_ANDROID) {
                val custom = familyDimensions(ClientFamily.CUSTOM_ANDROID)
                val mustHave =
                    listOf(
                        CompatDimension.CUSTOM_PROTECT_TUNNEL_SOCKETS,
                        CompatDimension.CUSTOM_ON_REVOKE,
                        CompatDimension.CUSTOM_NO_ALLOW_BYPASS,
                        CompatDimension.CUSTOM_NO_LOG_SECRETS,
                        CompatDimension.CUSTOM_PROFILE_SIGNATURE_EXPIRY_REVOCATION,
                    )
                if (!custom.containsAll(mustHave)) {
                    issues += MatrixValidationIssue(client, "custom Android client missing a fail-closed invariant")
                }
            }
        }
        return issues
    }
}
