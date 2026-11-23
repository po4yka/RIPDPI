package com.poyka.ripdpi.data

import com.google.protobuf.InvalidProtocolBufferException
import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardEditionAppSettingsTest {
    @Test
    fun `read drops retired top level flags and keeps all other fields`() {
        val stored = settingsWireFixture(includeDeviceScopedField = true)
        val settings = decodeStoredAppSettings(stored)

        assertEquals(5432, settings.proxyPort)
        assertFalse(settings.externalPacketOperationsEnabled())
        assertFalse(settings.advancedTcpSignatureRequested())
        assertEquals(settings, settings.toBuilder().setAdvancedTcpSignatureRequested(true).build())
        assertArrayEquals(settingsWireFixture(includeDeviceScopedField = false), settings.toByteArray())
    }

    @Test
    fun `write and export drop the retired flags from protobuf unknown fields`() {
        val bytes = settingsWireFixture(includeDeviceScopedField = true)
        val legacy = AppSettings.parseFrom(bytes)
        assertArrayEquals(bytes, legacy.toByteArray())
        val expected = settingsWireFixture(includeDeviceScopedField = false)

        assertArrayEquals(expected, legacy.withoutDeviceScopedEditionSettings().toByteArray())
        assertArrayEquals(expected, encodeStoredAppSettings(legacy))
        assertTrue(sensitiveEditionSettingsPaths.isEmpty())
    }

    @Test
    fun `invalid protobuf still fails as corrupted data`() {
        assertThrows(InvalidProtocolBufferException::class.java) {
            decodeStoredAppSettings(byteArrayOf(0x0C))
        }
    }
}
