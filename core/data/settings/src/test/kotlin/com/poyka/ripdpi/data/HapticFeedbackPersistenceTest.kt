package com.poyka.ripdpi.data

import com.google.protobuf.CodedOutputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class HapticFeedbackPersistenceTest {
    @Test
    fun `full JSON backup preserves disabled haptic feedback`() {
        val restored = appSettingsFromJson("""{"hapticFeedbackDisabled":true}""")
        val exported = appSettingsJson.parseToJsonElement(restored.toJson()).jsonObject

        assertEquals(JsonPrimitive(true), exported["hapticFeedbackDisabled"])
    }

    @Test
    fun `dynamic color defaults off and survives JSON round trip`() {
        assertFalse(AppSettingsSerializer.defaultValue.dynamicColorEnabled)
        assertFalse(appSettingsFromJson("""{"appTheme":"dark"}""").dynamicColorEnabled)

        val on =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setDynamicColorEnabled(true)
                .build()
        assertTrue(appSettingsFromJson(on.toJson()).dynamicColorEnabled)
    }

    @Test
    fun `system haptic feedback survives JSON round trip`() {
        val settings =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setHapticFeedbackDisabled(false)
                .build()

        assertFalse(appSettingsFromJson(settings.toJson()).hapticFeedbackDisabled)
    }

    @Test
    fun `legacy JSON retains system haptic feedback`() {
        val restored = appSettingsFromJson("""{"appTheme":"dark","mode":"proxy"}""")

        assertFalse(restored.hapticFeedbackDisabled)
        assertEquals("dark", restored.appTheme)
        assertEquals("proxy", restored.ripdpiMode)
    }

    @Test
    fun `canonical defaults retain system haptic feedback`() {
        assertFalse(AppSettingsSerializer.defaultValue.hapticFeedbackDisabled)
    }

    @Test
    fun `legacy protobuf retains system haptic feedback and existing settings`() =
        runTest {
            val legacyBytes =
                ByteArrayOutputStream()
                    .also { output ->
                        CodedOutputStream.newInstance(output).also { coded ->
                            coded.writeString(1, "dark")
                            coded.writeString(2, "proxy")
                            coded.writeInt32(8, 2080)
                            coded.flush()
                        }
                    }.toByteArray()

            val restored = AppSettingsSerializer.readFrom(ByteArrayInputStream(legacyBytes))

            assertFalse(restored.hapticFeedbackDisabled)
            assertEquals("dark", restored.appTheme)
            assertEquals("proxy", restored.ripdpiMode)
            assertEquals(2080, restored.proxyPort)
        }
}
