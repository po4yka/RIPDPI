package com.poyka.ripdpi.data

import com.poyka.ripdpi.serialization.RipDpiEncodeDefaultsJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SelectorRelayProfileMappingTest {
    @Test
    fun `canonical selector mapping has stable timestamp and complete credential bytes`() {
        val member = member("original")
        val first = checkNotNull(mapRelayProfile(member))
        val second = checkNotNull(mapRelayProfile(member))

        assertEquals(0L, first.credentials.updatedAtEpochMillis)
        assertEquals(first, second)
        assertEquals(encoded(first.credentials), encoded(second.credentials))
        assertEquals(member.password, first.credentials.trojanPassword)
        assertEquals(member.server, first.profile.server)
        assertEquals(member.serverName, first.profile.serverName)
    }

    @Test
    fun `changing selector secret remains significant in canonical credential material`() {
        val original = checkNotNull(mapRelayProfile(member("original")))
        val changed = checkNotNull(mapRelayProfile(member("changed")))

        assertEquals(original.profile, changed.profile)
        assertNotEquals(encoded(original.credentials), encoded(changed.credentials))
        assertEquals(0L, changed.credentials.updatedAtEpochMillis)
    }

    @Test
    fun `hysteria2 certificate pin reaches the relay credentials with insecure (REL-3)`() {
        val pin = "ab".repeat(32)
        val profile =
            ProxyProfile.Hysteria2(
                id = "hy2",
                displayName = "hy2",
                groupId = "group-a",
                server = "hy2.example",
                serverPort = 443,
                password = "hy2-fixture-pw",
                insecure = true,
                pinSha256 = pin,
            )

        val credentials = checkNotNull(mapRelayProfile(profile)).credentials

        assertEquals(true, credentials.hysteriaInsecure)
        assertEquals(pin, credentials.hysteriaPinSha256)
    }

    private fun member(label: String) =
        ProxyProfile.Trojan(
            id = "shared-member",
            displayName = "Member",
            groupId = "group-a",
            server = "relay.example",
            serverPort = 443,
            serverName = "relay.example",
            password = "fixture-" + label,
        )

    private fun encoded(credentials: RelayCredentialRecord) =
        RipDpiEncodeDefaultsJson.encodeToString(RelayCredentialRecord.serializer(), credentials)
}
