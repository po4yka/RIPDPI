package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.RelayKindAnyTls
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindOff
import com.poyka.ripdpi.data.RelayKindShadowsocks
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindTor
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVlessReality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cross-layer drift guard for the [RelayKindDescriptor] platform.
 *
 * A new relay kind must be added through the descriptor table, the resolver
 * registrations, the Rust `RELAY_TRANSPORT_DESCRIPTORS` table, and the
 * `relay_kind` comment in `app_settings.proto`. These tests fail when any one
 * of those four surfaces drifts away from the others.
 */
class RelayKindDescriptorDriftTest {
    @Test
    fun `relay kind descriptor ids are unique and non-blank`() {
        val kindIds = RelayKindDescriptors.map { it.kindId }
        assertEquals("descriptor kind ids must be unique", kindIds.size, kindIds.toSet().size)
        assertTrue("descriptor kind ids must be non-blank", kindIds.all { it.isNotBlank() })
    }

    @Test
    fun `unrecognised relay kind has no descriptor`() {
        assertNull(relayKindDescriptor("definitely_not_a_relay_kind"))
    }

    @Test
    fun `every relay kind descriptor is registered to a resolver`() {
        val registrations = defaultRegistrations()
        assertEquals(
            "every descriptor must produce exactly one resolver registration",
            RelayKindDescriptors.size,
            registrations.size,
        )
        assertEquals(
            "every descriptor must be registered to a resolver",
            RelayKindDescriptors.map { it.kindId }.toSet(),
            registrations.map { it.descriptor.kindId }.toSet(),
        )
        // The `off` state is handled explicitly by the catch-all resolver.
        val offRegistration = registrations.single { it.descriptor.kindId == RelayKindOff }
        assertTrue(
            "the off state must resolve to the default resolver",
            offRegistration.resolver is DefaultRelayKindResolver,
        )
    }

    @Test
    fun `every relay kind resolver owns at least one descriptor`() {
        val expected =
            setOf(
                MasqueRelayKindResolver::class,
                CloudflareTunnelRelayKindResolver::class,
                SnowflakeRelayKindResolver::class,
                ChainRelayKindResolver::class,
                ShadowTlsRelayKindResolver::class,
                TrojanRelayKindResolver::class,
                NaiveRelayKindResolver::class,
                LocalPathRelayKindResolver::class,
                TorRelayKindResolver::class,
                DefaultRelayKindResolver::class,
            )
        assertEquals(
            "every resolver must own at least one descriptor registration",
            expected,
            defaultRegistrations().map { it.resolver::class }.toSet(),
        )
    }

    @Test
    fun `every registration resolver claims its descriptor kind`() {
        defaultRegistrations().forEach { registration ->
            assertTrue(
                "${registration.resolver::class.simpleName} must claim ${registration.descriptor.kindId}",
                registration.resolver.supports(registration.descriptor.kindId),
            )
        }
    }

    @Test
    fun `every rust descriptor kind has a kotlin descriptor`() {
        val rustKinds = parseRustDescriptorKinds()
        assertTrue("expected to parse Rust descriptor kinds", rustKinds.isNotEmpty())
        val kotlinKinds = RelayKindDescriptors.map { it.kindId }.toSet()
        rustKinds.forEach { rustKind ->
            assertTrue("Rust descriptor kind '$rustKind' has no Kotlin RelayKindDescriptor", rustKind in kotlinKinds)
        }
    }

    @Test
    fun `kotlin capability fields match the rust descriptor table`() {
        // kindId -> [tcp, udp, reusable, supportsOutboundBindIp] — a literal
        // mirror of RELAY_TRANSPORT_DESCRIPTORS in transport_descriptor.rs.
        val rustPinned =
            mapOf(
                "hysteria2" to listOf(true, true, true, false),
                "tuic_v5" to listOf(true, true, true, true),
                "vless_reality" to listOf(true, false, false, true),
                "cloudflare_tunnel" to listOf(true, false, true, true),
                "chain_relay" to listOf(true, false, false, true),
                "masque" to listOf(true, true, true, false),
                "anytls" to listOf(true, true, true, true),
                "shadowtls_v3" to listOf(true, false, false, true),
                "trojan" to listOf(true, true, false, true),
                "shadowsocks" to listOf(true, true, false, true),
                "tor" to listOf(true, false, true, false),
                "naiveproxy" to listOf(true, false, false, true),
                "ssh" to listOf(true, false, false, false),
            )
        rustPinned.forEach { (kindId, caps) ->
            val descriptor = relayKindDescriptor(kindId) ?: error("missing Kotlin descriptor for $kindId")
            assertEquals(
                "capability drift for '$kindId' vs the Rust descriptor table",
                caps,
                listOf(descriptor.tcp, descriptor.udp, descriptor.reusable, descriptor.supportsOutboundBindIp),
            )
        }
    }

    @Test
    fun `every relay_kind in the proto comment has a kotlin descriptor`() {
        val protoKinds = parseProtoRelayKinds()
        assertTrue("expected to parse relay_kind values from the proto comment", protoKinds.isNotEmpty())
        val kotlinKinds = RelayKindDescriptors.map { it.kindId }.toSet()
        protoKinds.forEach { protoKind ->
            assertTrue("proto relay_kind '$protoKind' has no Kotlin RelayKindDescriptor", protoKind in kotlinKinds)
        }
    }

    @Test
    fun `kotlin-owned descriptor facts are pinned`() {
        assertEquals(RelayFinalmaskSupport.SUPPORTED, relayKindDescriptor("cloudflare_tunnel")?.finalmaskSupport)
        assertEquals(RelayFinalmaskSupport.SUB_MODE_DEPENDENT, relayKindDescriptor("vless_reality")?.finalmaskSupport)
        assertEquals(RelayFinalmaskSupport.NONE, relayKindDescriptor("hysteria2")?.finalmaskSupport)

        assertEquals(RelayConfigBacking.PROFILE_BACKED, relayKindDescriptor("chain_relay")?.configBacking)
        assertEquals(RelayConfigBacking.PROFILE_BACKED, relayKindDescriptor("shadowtls_v3")?.configBacking)
        assertEquals(RelayConfigBacking.INLINE_SETTINGS_BACKED, relayKindDescriptor("masque")?.configBacking)

        val subprocessKinds = RelayKindDescriptors.filter { it.subprocessBacked }.map { it.kindId }.toSet()
        assertEquals(setOf("naiveproxy", "snowflake", "webtunnel", "obfs4"), subprocessKinds)
    }

    @Test
    fun `udp gate accepts udp-capable kinds through the descriptor`() {
        listOf(
            RelayKindHysteria2,
            RelayKindAnyTls,
            RelayKindMasque,
            RelayKindShadowsocks,
            RelayKindTrojan,
            RelayKindTuicV5,
        ).forEach { kind ->
            validateSharedRelayTransportFeatures(RipDpiRelayConfig(kind = kind, udpEnabled = true))
        }
    }

    @Test
    fun `udp gate rejects udp for tcp-only kinds through the descriptor`() {
        listOf(RelayKindVlessReality, RelayKindSnowflake, RelayKindTor, RelayKindOff).forEach { kind ->
            assertThrows(IllegalArgumentException::class.java) {
                validateSharedRelayTransportFeatures(RipDpiRelayConfig(kind = kind, udpEnabled = true))
            }
        }
    }

    private fun defaultRegistrations(): List<RelayKindRegistration> =
        createDefaultRelayKindResolverRegistry(
            relayProfileStore = TestRelayProfileStore(),
            relayCredentialStore = TestRelayCredentialStore(),
            cloudflareMasqueGeohashResolver =
                object : CloudflareMasqueGeohashResolver {
                    override suspend fun resolveHeaderValue(): String? = null
                },
            masquePrivacyPassProvider = StaticMasquePrivacyPassProvider(),
        ).registrations

    private fun parseRustDescriptorKinds(): Set<String> {
        val source =
            File(repoRoot(), "native/rust/crates/ripdpi-relay-core/src/transport_descriptor.rs").readText()
        return Regex("""kind_id:\s*"([a-z0-9_]+)"""").findAll(source).map { it.groupValues[1] }.toSet()
    }

    private fun parseProtoRelayKinds(): Set<String> {
        val source = File(repoRoot(), "core/data/model/src/main/proto/app_settings.proto").readText()
        val fieldLine = source.lineSequence().first { it.contains("string relay_kind = 171;") }
        val comment = fieldLine.substringAfter("//", "")
        return Regex(""""([a-z0-9_]+)"""").findAll(comment).map { it.groupValues[1] }.toSet()
    }

    private fun repoRoot(): File {
        var current = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (!File(current, "settings.gradle.kts").exists()) {
            current = current.parentFile ?: error("unable to locate the repository root")
        }
        return current
    }
}
