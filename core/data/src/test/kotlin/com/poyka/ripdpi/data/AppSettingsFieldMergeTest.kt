package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsFieldMergeTest {
    @Test
    fun `relay fields come from the edit as one unit when a concurrent write selected another profile`() {
        val base =
            AppSettings
                .newBuilder()
                .setRelayProfileId("profile-a")
                .setRelayKind(RelayKindVlessReality)
                .setRelayServer("a.example")
                .setRelayRealityPublicKey("key-a")
                .setRelayRealityShortId("aa")
                .build()
        // The editor saves profile A: only its server changed.
        val after = base.toBuilder().setRelayServer("a2.example").build()
        // Meanwhile profile B was selected, and an unrelated setting changed.
        val current =
            base
                .toBuilder()
                .setRelayProfileId("profile-b")
                .setRelayServer("b.example")
                .setRelayRealityPublicKey("key-b")
                .setRelayRealityShortId("bb")
                .setProxyPort(base.proxyPort + 1)
                .build()

        val merged = mergeChangedSettingsFields(base, after, current)

        assertEquals("profile-a", merged.relayProfileId)
        assertEquals("a2.example", merged.relayServer)
        assertEquals("key-a", merged.relayRealityPublicKey)
        assertEquals("aa", merged.relayRealityShortId)
        assertEquals("non-relay concurrent write was reverted", base.proxyPort + 1, merged.proxyPort)
    }

    @Test
    fun `an edit that leaves relay fields alone keeps a concurrent relay selection`() {
        val base = AppSettings.newBuilder().setRelayProfileId("profile-a").build()
        val after = base.toBuilder().setProxyPort(base.proxyPort + 1).build()
        val current =
            base
                .toBuilder()
                .setRelayProfileId("profile-b")
                .setRelayServer("b.example")
                .build()

        val merged = mergeChangedSettingsFields(base, after, current)

        assertEquals("profile-b", merged.relayProfileId)
        assertEquals("b.example", merged.relayServer)
        assertEquals(base.proxyPort + 1, merged.proxyPort)
    }

    @Test
    fun `the relay field group lists every relay_ field of AppSettings`() {
        // Generated field-number constants follow the proto names, so this tracks new relay_* fields.
        val declared =
            AppSettings::class.java.fields
                .filter { it.name.startsWith("RELAY_") && it.name.endsWith("_FIELD_NUMBER") }
                .map { it.getInt(null) }
                .toSet()

        assertEquals(declared, RelaySettingsFieldNumbers)
    }
}
