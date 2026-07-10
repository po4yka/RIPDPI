package com.poyka.ripdpi.data

import com.google.protobuf.CodedOutputStream
import com.google.protobuf.WireFormat
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AppSettingsSerializerMigrationTest {
    @Test
    fun `legacy xhttp tags migrate without overwriting current fields`() =
        runTest {
            val legacy =
                legacyBytes(
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setRelayEnabled(true)
                        .setRelayKind(RelayKindVlessReality)
                        .build(),
                    transport = "xhttp",
                    path = "/legacy-path",
                    host = "legacy.example",
                )

            val migrated = AppSettingsSerializer.readFrom(ByteArrayInputStream(legacy))

            assertEquals("xhttp", migrated.relayVlessTransport)
            assertEquals("/legacy-path", migrated.relayXhttpPath)
            assertEquals("legacy.example", migrated.relayXhttpHost)
            assertTrue(migrated.strategyChainYaml.isEmpty())
        }

    @Test
    fun `tag 214 strategy yaml is not mistaken for relay transport`() =
        runTest {
            val current =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setRelayEnabled(false)
                    .setRelayKind(RelayKindOff)
                    .setStrategyChainYaml("xhttp")
                    .build()

            val decoded = AppSettingsSerializer.readFrom(ByteArrayInputStream(current.toByteArray()))

            assertEquals("xhttp", decoded.strategyChainYaml)
            assertTrue(decoded.relayVlessTransport.isEmpty())
        }

    @Test
    fun `current relay fields win and unrelated unknown fields survive migration`() =
        runTest {
            val current =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setRelayEnabled(true)
                    .setRelayKind(RelayKindVlessReality)
                    .setRelayVlessTransport("reality_tcp")
                    .setRelayXhttpPath("/current")
                    .setRelayXhttpHost("current.example")
                    .build()
            val legacy =
                legacyBytes(
                    current,
                    transport = "xhttp",
                    path = "/legacy",
                    host = "legacy.example",
                    unknownValue = "preserve-me",
                )

            val migrated = AppSettingsSerializer.readFrom(ByteArrayInputStream(legacy))
            val persisted = ByteArrayOutputStream().also { AppSettingsSerializer.writeTo(migrated, it) }.toByteArray()

            assertEquals("reality_tcp", migrated.relayVlessTransport)
            assertEquals("/current", migrated.relayXhttpPath)
            assertEquals("current.example", migrated.relayXhttpHost)
            assertTrue(hasStringField(persisted, 999, "preserve-me"))
        }

    private fun legacyBytes(
        current: AppSettings,
        transport: String,
        path: String,
        host: String,
        unknownValue: String? = null,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        current.writeTo(output)
        val coded = CodedOutputStream.newInstance(output)
        coded.writeString(214, transport)
        coded.writeString(215, path)
        coded.writeString(216, host)
        if (unknownValue != null) coded.writeString(999, unknownValue)
        coded.flush()
        return output.toByteArray()
    }

    private fun hasStringField(
        bytes: ByteArray,
        expectedField: Int,
        expectedValue: String,
    ): Boolean {
        val input =
            com.google.protobuf.CodedInputStream
                .newInstance(bytes)
        while (!input.isAtEnd) {
            val tag = input.readTag()
            if (tag == 0) return false
            val fieldNumber = WireFormat.getTagFieldNumber(tag)
            if (fieldNumber == expectedField &&
                WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED
            ) {
                return input.readStringRequireUtf8() == expectedValue
            }
            input.skipField(tag)
        }
        return false
    }
}
