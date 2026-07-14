package com.poyka.ripdpi.data

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.WireFormat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AppSettingsSerializerUnknownFieldTest {
    @Test
    fun `standard protobuf unknown fields survive serializer round trip`() =
        runTest {
            val bytes =
                ByteArrayOutputStream()
                    .also { output ->
                        AppSettingsSerializer.defaultValue.writeTo(output)
                        CodedOutputStream.newInstance(output).also { coded ->
                            coded.writeString(999, "preserve-me")
                            coded.flush()
                        }
                    }.toByteArray()

            val decoded = AppSettingsSerializer.readFrom(ByteArrayInputStream(bytes))
            val persisted = ByteArrayOutputStream().also { AppSettingsSerializer.writeTo(decoded, it) }.toByteArray()

            assertTrue(hasStringField(persisted, 999, "preserve-me"))
        }

    private fun hasStringField(
        bytes: ByteArray,
        expectedField: Int,
        expectedValue: String,
    ): Boolean {
        val input = CodedInputStream.newInstance(bytes)
        var matches = false
        while (!input.isAtEnd && !matches) {
            val tag = input.readTag()
            matches =
                tag != 0 &&
                WireFormat.getTagFieldNumber(tag) == expectedField &&
                WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED
            if (matches) {
                matches = input.readStringRequireUtf8() == expectedValue
            } else if (tag != 0) {
                input.skipField(tag)
            }
        }
        return matches
    }
}
