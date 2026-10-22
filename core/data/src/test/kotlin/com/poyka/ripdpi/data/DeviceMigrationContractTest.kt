package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.subscription.MigrationDelivery
import com.poyka.ripdpi.data.subscription.MigrationMetadata
import com.poyka.ripdpi.data.subscription.MigrationPathTest
import com.poyka.ripdpi.data.subscription.SingBoxParseResult
import com.poyka.ripdpi.data.subscription.SingBoxSubscriptionParser
import com.poyka.ripdpi.data.subscription.exportReceipt
import com.poyka.ripdpi.data.subscription.migrationSha256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMigrationContractTest {
    private val golden =
        Json
            .parseToJsonElement(
                checkNotNull(
                    javaClass.classLoader?.getResource(
                        "contract/device-migration.golden.json",
                    ),
                ).readText(),
            ).jsonObject
    private val metadata = golden.getValue("metadata").jsonObject

    @Test
    fun `shared vectors bind exact bytes and omit every secret`() {
        val bytes =
            golden
                .getValue("payload_utf8")
                .jsonPrimitive.content
                .toByteArray(Charsets.UTF_8)
        val identity =
            MigrationMetadata(
                metadata.getValue("migration_id").jsonPrimitive.content,
                metadata.getValue("delivery_id").jsonPrimitive.content,
                metadata.getValue("target_tags").jsonArray.map { it.jsonPrimitive.content },
                metadata.getValue("receipt_key").jsonPrimitive.content,
                metadata.getValue("awg_previous_tags").jsonObject.mapValues { it.value.jsonPrimitive.content },
            )
        golden.getValue("vectors").jsonArray.forEach { vector ->
            val expected = vector.jsonObject.getValue("receipt").jsonObject
            val testedAt = expected.getValue("tested_at_ms").jsonPrimitive.long
            val delivery =
                MigrationDelivery(
                    identity,
                    migrationSha256(bytes),
                    expected.getValue("received_at_ms").jsonPrimitive.long,
                    emptyList(),
                    if (testedAt == 0L) {
                        null
                    } else {
                        MigrationPathTest(
                            expected.getValue("tested_tag").jsonPrimitive.content,
                            testedAt,
                            "local-only",
                        )
                    },
                )
            val encoded = delivery.exportReceipt()
            assertEquals(expected, Json.parseToJsonElement(encoded))
            assertFalse(encoded.contains(identity.receiptKey))
            assertFalse(encoded.contains("local-only"))
        }
        assertFalse(migrationSha256(bytes) == migrationSha256(bytes.toString(Charsets.UTF_8).trim().toByteArray()))
    }

    @Test
    fun `valid exact metadata survives parser and malformed metadata rejects whole bundle`() {
        val valid =
            JsonObject(
                metadata +
                    mapOf(
                        "target_tags" to JsonArray(listOf(JsonPrimitive("green"))),
                        "awg_previous_tags" to JsonObject(emptyMap()),
                    ),
            )
        val success = SingBoxSubscriptionParser.parse(bundle(valid), "group") as SingBoxParseResult.Success
        assertEquals(listOf("green"), success.migration?.targetTags)
        val invalid =
            listOf(
                JsonObject(valid - "receipt_key"),
                JsonObject(valid - "awg_previous_tags"),
                JsonObject(valid + ("awg_previous_tags" to JsonObject(mapOf("missing" to JsonPrimitive("old"))))),
                JsonObject(valid + ("awg_previous_tags" to JsonObject(mapOf("green" to JsonPrimitive("old"))))),
                JsonObject(valid + ("extra" to JsonPrimitive("secret"))),
                JsonObject(valid + ("schema_version" to JsonPrimitive("1"))),
                JsonObject(valid + ("receipt_key" to JsonPrimitive("A".repeat(64)))),
                JsonObject(valid + ("migration_id" to JsonPrimitive("not-a-uuid"))),
                JsonObject(valid + ("target_tags" to JsonArray(listOf(JsonPrimitive("missing"))))),
                JsonObject(
                    valid + ("target_tags" to JsonArray(listOf(JsonPrimitive("green"), JsonPrimitive("green")))),
                ),
            )
        invalid.forEach { assertTrue(SingBoxSubscriptionParser.parse(bundle(it), "group") is SingBoxParseResult.Error) }
        assertTrue(
            SingBoxSubscriptionParser.parse(bundle(valid, duplicate = true), "group") is SingBoxParseResult.Error,
        )
    }

    private fun bundle(
        value: JsonObject,
        duplicate: Boolean = false,
    ): String {
        val outbound = """{
            "type":"hysteria2","tag":"green","server":"green.example",
            "server_port":443,"password":"fixture"
        }"""
        return """{
            "outbounds":[$outbound${if (duplicate) ",$outbound" else ""}],
            "ripdpi":{"schema_version":1,"migration":$value}
        }"""
    }
}
