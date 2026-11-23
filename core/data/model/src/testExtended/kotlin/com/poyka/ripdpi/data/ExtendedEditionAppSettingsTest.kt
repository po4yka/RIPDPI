package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtendedEditionAppSettingsTest {
    @Test
    fun `old root setting does not authorize a helper launch`() {
        val oldSettings = settingsWireFixture(includeDeviceScopedField = true, includeAuthorizedField = false)
        val expected =
            AppSettings.parseFrom(
                settingsWireFixture(includeDeviceScopedField = false, includeSignatureField = true),
            )
        val settings = decodeStoredAppSettings(oldSettings)

        assertFalse(settings.externalPacketOperationsEnabled())
        assertArrayEquals(expected.toByteArray(), settings.toByteArray())
        assertArrayEquals(expected.toByteArray(), encodeStoredAppSettings(AppSettings.parseFrom(oldSettings)))
    }

    @Test
    fun `read and write maintain the opt-in flag while export clears it`() {
        val bytes = settingsWireFixture(includeDeviceScopedField = true)
        val settings = decodeStoredAppSettings(bytes)
        val exported = settings.withoutDeviceScopedEditionSettings()

        assertTrue(settings.externalPacketOperationsEnabled())
        // Protobuf emits known fields before unknown fields, so compare decoded content here.
        assertTrue(decodeStoredAppSettings(encodeStoredAppSettings(settings)).externalPacketOperationsEnabled())
        assertTrue(settings.advancedTcpSignatureRequested())
        assertFalse(
            settings
                .toBuilder()
                .setAdvancedTcpSignatureRequested(false)
                .build()
                .advancedTcpSignatureRequested(),
        )
        assertTrue(exported.advancedTcpSignatureRequested())
        assertFalse(exported.externalPacketOperationsEnabled())
        val expected =
            AppSettings.parseFrom(
                settingsWireFixture(includeDeviceScopedField = false, includeSignatureField = true),
            )
        assertArrayEquals(expected.toByteArray(), exported.toByteArray())
    }
}
