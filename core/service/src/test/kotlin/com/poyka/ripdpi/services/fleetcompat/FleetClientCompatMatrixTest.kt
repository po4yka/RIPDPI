package com.poyka.ripdpi.services.fleetcompat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Client-compatibility regression matrix test for fleet profiles.
 *
 * This is the runnable release gate for [FleetClientCompatMatrix]: it asserts
 * that every supported client is covered against the shared compatibility
 * dimensions plus its family-specific ones, that the matrix is internally
 * consistent, and that the family invariants (nodes-only vs full-policy,
 * fail-closed custom-Android) hold. Drop a client or a required dimension and
 * this test goes red.
 *
 * It complements the phase-1/2 golden-file harness
 * (`core/data/.../fleet/FleetCompatGoldenFileTest.kt`): that suite locks the
 * bundle-import surface; this one locks the client-coverage surface.
 */
class FleetClientCompatMatrixTest {
    @Test
    fun `matrix validates with no structural issues`() {
        val issues = FleetClientCompatMatrix.validate()
        assertTrue("matrix must be structurally sound, found: $issues", issues.isEmpty())
    }

    @Test
    fun `all eight enumerated fleet clients are present`() {
        val names = FleetClient.entries.map { it.displayName }
        assertEquals(8, FleetClient.entries.size)
        assertTrue(names.any { it.contains("custom Android") })
        assertTrue(names.any { it.contains("sing-box / SFA") })
        assertTrue(names.any { it.contains("v2rayNG") })
        assertTrue(names.any { it.contains("NekoBox") })
        assertTrue(names.any { it.contains("husi") })
        assertTrue(names.any { it.contains("V2Box") })
        assertTrue(names.any { it.contains("v2rayN (Windows)") })
        assertTrue(names.any { it.contains("sing-box CLI") })
    }

    @Test
    fun `shared matrix covers every required cross-client dimension`() {
        val shared = FleetClientCompatMatrix.sharedDimensions.toSet()
        // Acceptance criterion: import, credential scope, selector, urltest,
        // TUN, kill switch, DNS, IPv6, network transitions, revocation, logs,
        // and update migration.
        val required =
            setOf(
                CompatDimension.IMPORT,
                CompatDimension.CREDENTIAL_SCOPE,
                CompatDimension.SELECTOR,
                CompatDimension.URLTEST,
                CompatDimension.TUN,
                CompatDimension.KILL_SWITCH,
                CompatDimension.DNS,
                CompatDimension.IPV6,
                CompatDimension.NETWORK_TRANSITIONS,
                CompatDimension.REVOCATION,
                CompatDimension.LOGS,
                CompatDimension.UPDATE_MIGRATION,
            )
        assertEquals(required, shared)
    }

    @Test
    fun `every client is exercised against all shared dimensions`() {
        val shared = FleetClientCompatMatrix.sharedDimensions
        FleetClient.entries.forEach { client ->
            val dimensions = FleetClientCompatMatrix.dimensionsFor(client)
            assertTrue(
                "${client.displayName} must cover every shared dimension",
                dimensions.containsAll(shared),
            )
        }
    }

    @Test
    fun `every cell records both app and embedded core versions`() {
        // Acceptance criterion: compatibility tests record both app and
        // embedded core versions.
        val offenders =
            FleetClientCompatMatrix.allCells().filterNot {
                it.recordsAppVersion && it.recordsCoreVersion
            }
        assertTrue("every cell must record app + core versions, offenders: $offenders", offenders.isEmpty())
    }

    @Test
    fun `singbox and SFA cover config check strict route dns hijack and rule-set update`() {
        val singbox = FleetClientCompatMatrix.familyDimensions(ClientFamily.SINGBOX).toSet()
        val required =
            setOf(
                CompatDimension.SINGBOX_CONFIG_CHECK,
                CompatDimension.SINGBOX_DEGRADED_CLOUDFLARE_EXCLUSION,
                CompatDimension.SINGBOX_STRICT_ROUTE,
                CompatDimension.SINGBOX_DNS_HIJACK,
                CompatDimension.SINGBOX_RULE_SET_UPDATE,
                CompatDimension.SINGBOX_REVOKED_PROFILE_REMOVAL,
            )
        assertEquals(required, singbox)
        // sing-box / SFA and the sing-box CLI share the family coverage.
        assertEquals(
            FleetClientCompatMatrix.dimensionsFor(FleetClient.SINGBOX_SFA).toSet(),
            FleetClientCompatMatrix.dimensionsFor(FleetClient.SINGBOX_CLI).toSet(),
        )
    }

    @Test
    fun `v2rayNG covers reality uri import lockdown leak checks and hysteria2 fallback`() {
        val v2rayng = FleetClientCompatMatrix.familyDimensions(ClientFamily.V2RAY_ANDROID).toSet()
        val required =
            setOf(
                CompatDimension.V2RAYNG_VLESS_REALITY_URI_IMPORT,
                CompatDimension.V2RAYNG_PER_DEVICE_SUBSCRIPTION,
                CompatDimension.V2RAYNG_VPN_MODE,
                CompatDimension.V2RAYNG_ANDROID_LOCKDOWN,
                CompatDimension.V2RAYNG_DNS_IPV6_LEAK_CHECK,
                CompatDimension.V2RAYNG_CORE_UPDATE,
                CompatDimension.V2RAYNG_HYSTERIA2_FALLBACK,
            )
        assertEquals(required, v2rayng)
    }

    @Test
    fun `nekobox and husi are nodes-only and verify routing and dns separately`() {
        // Acceptance criterion: NekoBox/husi treat subscriptions as nodes-only
        // unless full policy is explicitly supported, and verify routing/DNS
        // separately.
        listOf(FleetClient.NEKOBOX, FleetClient.HUSI).forEach { client ->
            assertTrue("${client.displayName} must be nodes-only", FleetClientCompatMatrix.isNodesOnly(client))
            assertFalse(
                "${client.displayName} must not be treated as full-policy",
                FleetClientCompatMatrix.isFullPolicy(client),
            )
            val dimensions = FleetClientCompatMatrix.dimensionsFor(client)
            assertTrue(
                "${client.displayName} must verify routing separately",
                dimensions.contains(CompatDimension.NODES_ONLY_ROUTING_VERIFIED_SEPARATELY),
            )
            assertTrue(
                "${client.displayName} must verify DNS separately",
                dimensions.contains(CompatDimension.NODES_ONLY_DNS_VERIFIED_SEPARATELY),
            )
        }
    }

    @Test
    fun `singbox and v2ray and ios and custom android are full-policy clients`() {
        listOf(
            FleetClient.CUSTOM_ANDROID,
            FleetClient.SINGBOX_SFA,
            FleetClient.SINGBOX_CLI,
            FleetClient.V2RAYNG,
            FleetClient.STREISAND_V2BOX,
            FleetClient.V2RAYN,
        ).forEach { client ->
            assertTrue("${client.displayName} must be full-policy", FleetClientCompatMatrix.isFullPolicy(client))
            assertFalse("${client.displayName} must not be nodes-only", FleetClientCompatMatrix.isNodesOnly(client))
        }
    }

    @Test
    fun `ios clients cover uri import manual fallback leak sleep-wake and update persistence`() {
        val ios = FleetClientCompatMatrix.familyDimensions(ClientFamily.IOS).toSet()
        val required =
            setOf(
                CompatDimension.IOS_URI_SUBSCRIPTION_IMPORT,
                CompatDimension.IOS_MANUAL_FALLBACK,
                CompatDimension.IOS_DNS_IPV6_LEAK,
                CompatDimension.IOS_SLEEP_WAKE,
                CompatDimension.IOS_WIFI_LTE,
                CompatDimension.IOS_APP_UPDATE_PERSISTENCE,
            )
        assertEquals(required, ios)
    }

    @Test
    fun `v2rayN covers xray and singbox cores tun elevation firewall kill switch and core update`() {
        val v2rayn = FleetClientCompatMatrix.familyDimensions(ClientFamily.V2RAY_DESKTOP).toSet()
        val required =
            setOf(
                CompatDimension.V2RAYN_XRAY_CORE,
                CompatDimension.V2RAYN_SINGBOX_CORE_IF_USED,
                CompatDimension.V2RAYN_TUN_ELEVATION,
                CompatDimension.V2RAYN_WINDOWS_FIREWALL_KILL_SWITCH,
                CompatDimension.V2RAYN_DNS_IPV6_LEAK,
                CompatDimension.V2RAYN_CORE_UPDATE,
            )
        assertEquals(required, v2rayn)
    }

    @Test
    fun `custom android covers the fail-closed vpnservice invariants`() {
        // Acceptance criterion: VpnService lifecycle, protect() for tunnel
        // sockets, onRevoke(), virtual DNS, no allowBypass, IPv4-only,
        // package visibility, foreground service resilience, no log secrets,
        // and profile signature/expiry/revocation.
        val custom = FleetClientCompatMatrix.familyDimensions(ClientFamily.CUSTOM_ANDROID).toSet()
        val required =
            setOf(
                CompatDimension.CUSTOM_VPNSERVICE_LIFECYCLE,
                CompatDimension.CUSTOM_PROTECT_TUNNEL_SOCKETS,
                CompatDimension.CUSTOM_ON_REVOKE,
                CompatDimension.CUSTOM_VIRTUAL_DNS,
                CompatDimension.CUSTOM_NO_ALLOW_BYPASS,
                CompatDimension.CUSTOM_IPV4_ONLY_BEHAVIOR,
                CompatDimension.CUSTOM_PACKAGE_VISIBILITY,
                CompatDimension.CUSTOM_FOREGROUND_SERVICE_RESILIENCE,
                CompatDimension.CUSTOM_NO_LOG_SECRETS,
                CompatDimension.CUSTOM_PROFILE_SIGNATURE_EXPIRY_REVOCATION,
            )
        assertEquals(required, custom)
    }

    @Test
    fun `family dimensions never bleed across families`() {
        // A regression where a family dimension is accidentally tagged SHARED
        // (or another family's scope) would let it leak onto unrelated clients.
        FleetClient.entries.forEach { client ->
            val familyOnly = FleetClientCompatMatrix.familyDimensions(client.family)
            val foreign =
                familyOnly.filter {
                    it.appliesTo != DimensionScope.forFamily(client.family)
                }
            assertTrue(
                "${client.displayName} family coverage leaked a foreign-scope dimension: $foreign",
                foreign.isEmpty(),
            )
        }
    }

    @Test
    fun `every dimension is reachable by at least one client`() {
        // Guard against an orphan dimension that no client is ever tested on.
        val covered = FleetClientCompatMatrix.allCells().map { it.dimension }.toSet()
        val orphans = CompatDimension.entries.filterNot { it in covered }
        assertTrue("no dimension may be orphaned, orphans: $orphans", orphans.isEmpty())
    }

    @Test
    fun `total cell count equals clients times their applicable dimensions`() {
        val expected = FleetClient.entries.sumOf { FleetClientCompatMatrix.dimensionsFor(it).size }
        assertEquals(expected, FleetClientCompatMatrix.allCells().size)
    }
}
