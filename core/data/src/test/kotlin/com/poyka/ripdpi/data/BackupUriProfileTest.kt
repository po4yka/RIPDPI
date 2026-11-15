package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.backup.BackupExporter
import com.poyka.ripdpi.data.backup.BackupVariant
import com.poyka.ripdpi.serialization.RipDpiEncodeDefaultsJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupUriProfileTest {
    private val tuic =
        ProxyProfile.Tuic(
            id = "tuic-fixture",
            displayName = "TUIC",
            groupId = "group-fixture",
            server = "tuic.example.com",
            serverPort = 443,
            uuid = "fixture-private-uuid",
            password = "fixture-private-password",
            serverName = "front.example.com",
            congestionControl = "new_reno",
            alpn = "h3,hq-interop",
        )

    private val trafficPattern =
        MieruTrafficPattern(
            seed = 0,
            unlockAll = false,
            tcpFragment = MieruTcpFragment(enable = false),
            padding = MieruPaddingPattern(maxMiddlePaddingLen = 0),
            lowEntropy = MieruLowEntropyPattern(mode = 0, maskRotation = 0),
        )

    private val mieru =
        ProxyProfile.Mieru(
            id = "mieru-fixture",
            displayName = "Mieru",
            groupId = "group-fixture",
            server = "mieru.example.com",
            serverPort = 2096,
            username = "fixture-private-user",
            password = "fixture-private-password",
            multiplexing = "off",
            mtu = 1380,
            handshakeMode = MieruHandshakeNoWait,
            trafficPattern = trafficPattern,
        )

    @Test
    fun `FULL backup round trip retains TUIC credentials and settings`() {
        val backup = export(BackupVariant.FULL, tuic)
        assertTrue(backup.containsCredentials)
        assertEquals(tuic, decode(backup.profiles.single()))
    }

    @Test
    fun `SHARE backup retains TUIC transport without credentials or profile ID`() {
        val backup = export(BackupVariant.SHARE, tuic)
        val profile = backup.profiles.single()
        val expected =
            RipDpiEncodeDefaultsJson.parseToJsonElement(
                """{"type":"tuic","displayName":"TUIC","groupId":"group-fixture", """ +
                    """"server":"tuic.example.com","serverPort":443,"serverName":"front.example.com", """ +
                    """"congestionControl":"new_reno","alpn":"h3,hq-interop"}""",
            )
        assertEquals(expected, profile)
        assertFalse(backup.containsCredentials)
        assertFalse(profile.toString().contains(tuic.uuid))
        assertFalse(profile.toString().contains(tuic.password))
    }

    @Test
    fun `FULL backup round trip retains Mieru optional presence and credentials`() {
        val patterns =
            listOf(
                null,
                MieruTrafficPattern(),
                trafficPattern,
                trafficPattern.copy(nonce = MieruNoncePattern(type = 0, minLen = 6, maxLen = 8)),
            )
        for (pattern in patterns) {
            for (handshake in listOf(null, MieruHandshakeDefault, MieruHandshakeNoWait)) {
                val profile = mieru.copy(handshakeMode = handshake, trafficPattern = pattern)
                val backup = export(BackupVariant.FULL, profile)
                assertTrue(backup.containsCredentials)
                assertEquals(profile, decode(backup.profiles.single()))
            }
        }
    }

    @Test
    fun `SHARE backup omits Mieru traffic pattern and credentials`() {
        val backup = export(BackupVariant.SHARE, mieru)
        val profile = backup.profiles.single()
        assertFalse("trafficPattern" in profile)
        assertEquals(JsonPrimitive("no_wait"), profile["handshakeMode"])
        assertEquals(JsonPrimitive("off"), profile["multiplexing"])
        assertEquals(JsonPrimitive(1380), profile["mtu"])
        assertFalse("username" in profile)
        assertFalse("password" in profile)
        assertFalse("id" in profile)
        assertFalse(backup.containsCredentials)
        assertFalse(profile.toString().contains(mieru.username))
        assertFalse(profile.toString().contains(mieru.password))
    }

    private fun export(
        variant: BackupVariant,
        profile: ProxyProfile,
    ) = BackupExporter.export(
        variant = variant,
        profiles = listOf(profile),
        groups = emptyList(),
        rules = emptyList(),
        settings = emptyMap(),
        createdAtEpochMillis = 0,
        appVersion = "fixture",
    )

    private fun decode(value: JsonObject): ProxyProfile =
        RipDpiEncodeDefaultsJson.decodeFromJsonElement(ProxyProfile.serializer(), value)
}
