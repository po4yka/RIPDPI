package com.poyka.ripdpi.data

import com.google.protobuf.CodedOutputStream
import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

class WarpWirePersistenceTest {
    @Test
    fun `untouched disabled settings remain off through JSON backup`() {
        for (stored in listOf(AppSettings.getDefaultInstance(), AppSettingsSerializer.defaultValue)) {
            assertEquals(WarpAmneziaPresetOff, stored.toWarpSettingsModel().amneziaPreset)
            val restored = appSettingsFromJson(stored.toJson())
            assertEquals(WarpAmneziaPresetOff, restored.toWarpSettingsModel().amneziaPreset)
            assertEquals(stored.warpAmneziaWireProfile, restored.warpAmneziaWireProfile)
        }
    }

    @Test
    fun `range headers and all signature slots survive JSON and protobuf round trips`() {
        val stored =
            AppSettings
                .newBuilder()
                .setWarpAmneziaWireProfile("awg-3.1-ranges-v1")
                .setWarpAmneziaEnabled(true)
                .setWarpAmneziaPreset("custom")
                .setWarpAmneziaH1("100-199")
                .setWarpAmneziaH2("200-299")
                .setWarpAmneziaH3("300-399")
                .setWarpAmneziaH4("400-499")
                .setWarpAmneziaI1("<b 0xf0><r 16><rd 4><rc 4><t>")
                .setWarpAmneziaI2("<b 0x02>")
                .setWarpAmneziaI3("<r 4>")
                .setWarpAmneziaI4("<rd 4>")
                .setWarpAmneziaI5("<rc 4>")
                .build()
        val json = stored.toJson()
        val roundTrip = AppSettings.parseFrom(appSettingsFromJson(json).toByteArray())
        val expected = stored.toWarpSettingsModel().amnezia
        expected.requireSupportedProfile()
        assertEquals(expected, roundTrip.toWarpSettingsModel().amnezia)
        assertEquals(json, appSettingsFromJson(json).toJson())
        assertThrows(IllegalArgumentException::class.java) {
            appSettingsFromJson(json.replace("\"100-199\"", "100"))
        }
    }

    @Test
    fun `old numeric protobuf headers remain unmodified and cannot activate as the new profile`() {
        val output = ByteArrayOutputStream()
        CodedOutputStream.newInstance(output).apply {
            writeBool(148, true)
            writeInt64(152, 100)
            writeInt64(153, 200)
            writeInt64(154, 300)
            writeInt64(155, 400)
            writeString(213, "custom")
            flush()
        }
        val old = AppSettings.parseFrom(output.toByteArray())
        val before = old.toByteArray()
        val settings = old.toWarpSettingsModel().amnezia
        assertEquals("", settings.wireProfile)
        assertThrows(IllegalArgumentException::class.java) { settings.requireSupportedProfile() }
        assertArrayEquals(before, old.toByteArray())
        assertThrows(IllegalArgumentException::class.java) { appSettingsFromJson(old.toJson()) }
    }
}
