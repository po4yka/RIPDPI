package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.backup.BackupAllowlist
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Reflection-based coverage test: every property of every [ProxyProfile] subtype
 * that appears in the JSON serialized form must be classified in [BackupAllowlist].
 *
 * A new field on any subtype will fail this test until it is added to the allowlist.
 */
class BackupAllowlistCoverageTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /**
     * Representative instances of every [ProxyProfile] subtype.
     * Maps protocol key (the @SerialName discriminator) to an instance.
     */
    private val protocolInstances: Map<String, ProxyProfile> =
        mapOf(
            "vless" to
                ProxyProfile.Vless(
                    id = "v-1",
                    displayName = "VLESS",
                    groupId = "g-1",
                    server = "vless.example.com",
                    serverPort = 443,
                    uuid = "uuid-fixture",
                ),
            "vless-reality" to
                ProxyProfile.VlessReality(
                    id = "vr-1",
                    displayName = "VLESS REALITY",
                    groupId = "g-1",
                    server = "reality.example.com",
                    serverPort = 443,
                    uuid = "uuid-fixture",
                    realityPublicKey = "pk-fixture",
                    realityShortId = "sid-fixture",
                    serverName = "front.example.com",
                ),
            "shadowsocks" to
                ProxyProfile.Shadowsocks(
                    id = "ss-1",
                    displayName = "SS",
                    groupId = "g-1",
                    server = "ss.example.com",
                    serverPort = 8388,
                    method = "aes-256-gcm",
                    password = "pw",
                ),
            "trojan" to
                ProxyProfile.Trojan(
                    id = "tr-1",
                    displayName = "Trojan",
                    groupId = "g-1",
                    server = "trojan.example.com",
                    serverPort = 443,
                    password = "pw",
                ),
            "hysteria2" to
                ProxyProfile.Hysteria2(
                    id = "hy2-1",
                    displayName = "Hy2",
                    groupId = "g-1",
                    server = "hy2.example.com",
                    serverPort = 8443,
                    password = "pw",
                ),
            "anytls" to
                ProxyProfile.AnyTls(
                    id = "anytls-1",
                    displayName = "AnyTLS",
                    groupId = "g-1",
                    server = "anytls.example.com",
                    serverPort = 443,
                    serverName = "front.example.com",
                    password = "pw",
                ),
            "mieru" to
                ProxyProfile.Mieru(
                    id = "mieru-1",
                    displayName = "Mieru",
                    groupId = "g-1",
                    server = "mieru.example.com",
                    serverPort = 2096,
                    username = "user",
                    password = "pw",
                ),
            "raw-config" to
                ProxyProfile.RawConfig(
                    id = "rc-1",
                    displayName = "Raw",
                    groupId = "g-1",
                    config = "{}",
                ),
        )

    @Test
    fun `every serialized field of every ProxyProfile subtype is classified in the allowlist`() {
        for ((protocolKey, instance) in protocolInstances) {
            val jsonObj =
                json
                    .encodeToJsonElement(ProxyProfile.serializer(), instance)
                    .jsonObject

            for (fieldName in jsonObj.keys) {
                // Skip the type discriminator itself — it is not a data field
                if (fieldName == "type") continue

                val classification =
                    runCatching {
                        BackupAllowlist.classificationFor(protocolKey, fieldName)
                    }.getOrElse { ex ->
                        throw AssertionError(
                            "Field '$fieldName' of protocol '$protocolKey' is not classified " +
                                "in BackupAllowlist. Add PUBLIC, REDACTED, or EXCLUDED entry.\n" +
                                "Cause: ${ex.message}",
                        )
                    }

                assertNotNull(
                    "Classification for '$protocolKey.$fieldName' must not be null",
                    classification,
                )
            }
        }
    }

    @Test
    fun `allowlist registry covers all known protocol keys`() {
        val registeredKeys = BackupAllowlist.allEntries().keys
        val expectedKeys = protocolInstances.keys
        for (key in expectedKeys) {
            assert(key in registeredKeys) {
                "Protocol '$key' is in test instances but missing from BackupAllowlist registry"
            }
        }
    }
}
